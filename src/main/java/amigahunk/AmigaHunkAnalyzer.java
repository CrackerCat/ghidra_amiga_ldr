/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package amigahunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import ghidra.app.plugin.core.analysis.ConstantPropagationContextEvaluator;
import ghidra.app.services.AbstractAnalyzer;
import ghidra.app.services.AnalyzerType;
import ghidra.app.util.importer.MessageLog;
import ghidra.framework.options.Options;
import ghidra.program.flatapi.FlatProgramAPI;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.data.ArrayDataType;
import ghidra.program.model.data.ByteDataType;
import ghidra.program.model.data.DataUtilities;
import ghidra.program.model.data.DataUtilities.ClearDataMode;
import ghidra.program.model.data.PointerDataType;
import ghidra.program.model.lang.Register;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.ParameterImpl;
import ghidra.program.model.listing.Program;
import ghidra.program.model.listing.Function.FunctionUpdateType;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.RefType;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.util.CodeUnitInsertionException;
import ghidra.program.util.SymbolicPropogator;
import ghidra.program.util.VarnodeContext;
import ghidra.util.exception.CancelledException;
import ghidra.util.exception.DuplicateNameException;
import ghidra.util.exception.InvalidInputException;
import ghidra.util.task.TaskMonitor;

public class AmigaHunkAnalyzer extends AbstractAnalyzer {

	private static final int imageBaseOffset = 0x10000;
	
	private final List<String> filter = new ArrayList<String>();
	private FdFunctionsInLibs funcsList;
	
	public static boolean isAmigaHunkLoader(Program program) {
		return program.getExecutableFormat().equalsIgnoreCase(AmigaHunkLoader.AMIGA_HUNK);
	}

	public AmigaHunkAnalyzer() {
		super("Amiga Library Calls", "Analyses calls to system libraries", AnalyzerType.INSTRUCTION_ANALYZER);
		
		filter.add(FdParser.EXEC_LIB);
		filter.add(FdParser.DOS_LIB);
	}

	@Override
	public boolean getDefaultEnablement(Program program) {
		return isAmigaHunkLoader(program);
	}

	@Override
	public boolean canAnalyze(Program program) {
		if (isAmigaHunkLoader(program)) {
			funcsList = new FdFunctionsInLibs();
			return true;
		}
		
		funcsList = null;
		return false;
	}
	
	@Override
	public void registerOptions(Options options, Program program) {
		
		if (funcsList == null) {
			return;
		}

		String[] libsList = funcsList.getLibsList(filter);
		for (String lib : libsList) {
			options.registerOption(lib.replace(FdParser.LIB_FD_EXT, "").toUpperCase(), true, null, String.format("Analyze calls from %s", lib));
		}
	}
	
	@Override
	public void optionsChanged(Options options, Program program) {
		super.optionsChanged(options, program);

		if (funcsList == null) {
			return;
		}
		
		filter.clear();
		
		String[] libsList = funcsList.getLibsList(filter);
		for (String lib : libsList) {
			if (options.getBoolean(lib.replace(FdParser.LIB_FD_EXT, "").toUpperCase(), false)) {
				filter.add(lib);
			}
		}
	}

	@Override
	public boolean added(Program program, AddressSetView set, TaskMonitor monitor, MessageLog log) {
		monitor.setMessage("Creating library functions...");
		
		FlatProgramAPI fpa = new FlatProgramAPI(program);
		
		try {
			String[] libs = funcsList.getLibsList(filter);
			
			for (String lib : libs) {
				int i = funcsList.findLibIndex(lib) + 1;
				
				if (i == 0) {
					continue;
				}
				
				createFunctionsSegment(fpa, lib, AmigaHunkLoader.getImageBase(imageBaseOffset) + i * 0x10000, funcsList.getFunctionTableByLib(lib), log);
			}
		} catch (InvalidInputException | DuplicateNameException | CodeUnitInsertionException e) {
			log.appendException(e);
			return false;
		}
		
		monitor.setMessage("Analysing library calls...");

		FunctionIterator fiter = program.getFunctionManager().getFunctions(set, true);
		while (fiter.hasNext()) {
			if (monitor.isCancelled()) {
				break;
			}

			Function func = fiter.next();
			Address start = func.getEntryPoint();
			
			SymbolicPropogator symEval = new SymbolicPropogator(program);
			symEval.setParamRefCheck(true);
			symEval.setReturnRefCheck(true);
			symEval.setStoredRefCheck(true);

			try {
				flowConstants(program, start, func.getBody(), symEval,  monitor);
			} catch (CancelledException e) {
				log.appendException(e);
				return false;
			}
		}

		return true;
	}
	
	private static void createFunctionsSegment(FlatProgramAPI fpa, String lib, long segAddr, FdLibFunctions funcs, MessageLog log) throws InvalidInputException, DuplicateNameException, CodeUnitInsertionException {
		if (fpa.getMemoryBlock(fpa.toAddr(segAddr)) != null) {
			return;
		}
		
		AmigaHunkLoader.createSegment(null, fpa, lib, segAddr, 0x1000, true, true, log);
		
		for (FdFunction func : funcs.getFunctions()) {
			Address funcAddress = fpa.toAddr(segAddr + Math.abs(func.getBias()));
			AmigaHunkLoader.setFunction(fpa, funcAddress, func.getName(true).replace(FdFunction.LIB_SPLITTER, "_"), log);
			Function function = fpa.getFunctionAt(funcAddress);
			function.setCustomVariableStorage(true);

			List<ParameterImpl> params = new ArrayList<>();

			Program program = fpa.getCurrentProgram();

			Map<String, String> args = func.getArgs();
			for (Entry<String, String> arg : args.entrySet()) {
				params.add(new ParameterImpl(arg.getKey(), PointerDataType.dataType, program.getRegister(arg.getValue()), program));
			}

			function.updateFunction(null, null, FunctionUpdateType.CUSTOM_STORAGE, true,
					SourceType.ANALYSIS, params.toArray(ParameterImpl[]::new));
			
			DataUtilities.createData(program, funcAddress, new ArrayDataType(ByteDataType.dataType, 6, -1), -1, false,
					ClearDataMode.CLEAR_ALL_UNDEFINED_CONFLICT_DATA);
		}
	}
	
	public AddressSetView flowConstants(final Program program, Address flowStart,
			AddressSetView flowSet, final SymbolicPropogator symEval, final TaskMonitor monitor)
			throws CancelledException {

		ConstantPropagationContextEvaluator eval =
			new ConstantPropagationContextEvaluator(true) {
				@Override
				public boolean evaluateContext(VarnodeContext context, Instruction instr) {
					String mnemonic = instr.getMnemonicString();

					if (mnemonic.equals("jsr") || mnemonic.equals("jmp")) {
						Object[] objs = instr.getOpObjects(0);
						Register reg = instr.getRegister(1);
						if (reg != null && reg.getName().equals("A6") && objs.length != 0 && (objs[0] instanceof Scalar)) {
							int val = (int)((Scalar)objs[0]).getSignedValue();
							
							if (val >= 0) {
								return false;
							}
							FdFunction[] funcs = funcsList.getLibsFunctionsByBias(filter, val);
							
							for (FdFunction func : funcs) {
								Address funcStart = program.getMemory().getBlock(func.getLib()).getStart().add(Math.abs(func.getBias()));
								instr.addOperandReference(1, funcStart, RefType.CALL_OVERRIDE_UNCONDITIONAL, SourceType.ANALYSIS);
							}
							
							for (int i = 0; i < funcs.length; ++i) {
								FdFunction func = funcs[i];
								
								if (!func.isPrivate() && func.getLib().equals(FdParser.EXEC_LIB)) {
									instr.setPrimaryMemoryReference(instr.getOperandReferences(1)[i]);
									break;
								}
							}
						}
					}
					return false;
				}
			};

		return symEval.flowConstants(flowStart, flowSet, eval, true, monitor);
	}
}
