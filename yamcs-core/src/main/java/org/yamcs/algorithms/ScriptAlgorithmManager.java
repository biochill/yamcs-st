package org.yamcs.algorithms;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.script.Bindings;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.ConfigurationException;
import org.yamcs.api.EventProducer;
import org.yamcs.xtce.CustomAlgorithm;
import org.yamcs.xtce.InputParameter;
import org.yamcs.xtce.OutputParameter;

/**
 * Handles the creation of algorithm executors for script algorithms for a given language and scriptEngine (currently javascript or python are supported).
 *  
 * Each algorithm is created as a function in the scriptEngine.
 * There might be multiple executors for the same algorithm: for example in the command verifier there will be one algorithm executor for each command.
 * However there will be only one function created in the script engine.
 *
 */
public class ScriptAlgorithmManager {
    final ScriptEngine scriptEngine;
    static final Logger log = LoggerFactory.getLogger(ScriptAlgorithmManager.class);
    final EventProducer eventProducer;
    
    public  ScriptAlgorithmManager(ScriptEngineManager scriptEngineManager, String language, List<String> libraryNames, EventProducer eventProducer) {
        this.eventProducer = eventProducer;
        scriptEngine = scriptEngineManager.getEngineByName(language);
        if (scriptEngine == null) {
            throw new ConfigurationException("Cannot get a script engine for language " + language);
        }
        if(libraryNames!=null) {
            loadLibraries(libraryNames);
        }
       
        // Put engine bindings in shared global scope - we want the variables in the libraries to be global
        Bindings commonBindings = scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE);
        Set<String> existingBindings = new HashSet<>(scriptEngineManager.getBindings().keySet());

        existingBindings.retainAll(commonBindings.keySet());
        if (!existingBindings.isEmpty()) {
            throw new ConfigurationException(
                    "Overlapping definitions found while loading libraries for language " + language + ": "
                            + existingBindings);
        }
        commonBindings.putAll(scriptEngineManager.getBindings());
        scriptEngineManager.setBindings(commonBindings);
    }
    
    
    private void loadLibraries(List<String> libraryNames) {
        try {
            for (String lib : libraryNames) {
                log.debug("Loading library {}", lib);
                File f = new File(lib);
                if (!f.exists()) {
                    throw new ConfigurationException("Algorithm library file '" + f + "' does not exist");
                }
                scriptEngine.put(ScriptEngine.FILENAME, f.getPath()); // Improves error msgs
                if (f.isFile()) {
                    try (FileReader fr = new FileReader(f)) {
                        scriptEngine.eval(fr);
                    }
                } else {
                    throw new ConfigurationException("Specified library is not a file: " + f);
                }
            }
        } catch (IOException e) { // Force exit. User should fix this before continuing
            throw new ConfigurationException("Cannot read from library file", e);
        } catch (ScriptException e) { // Force exit. User should fix this before continuing
            throw new ConfigurationException("Script error found in library file: " + e.getMessage(), e);
        }
    }


    ScriptAlgorithmExecutor createExecutor(CustomAlgorithm calg,  AlgorithmExecutionContext execCtx) {
        String functionName = calg.getQualifiedName().replace("/", "_");
        if(scriptEngine.get(functionName)==null) {
            String functionScript = generateFunctionCode(functionName, calg);
            log.debug("Evaluating script:\n{}", functionScript);
            try {
                //improve error messages as well as required for event generation to know from where it is called
                scriptEngine.put(ScriptEngine.FILENAME, calg.getQualifiedName());
                scriptEngine.eval(functionScript);
            } catch (ScriptException e) {
                eventProducer.sendWarning(EventProducer.TYPE_ALGO_COMPILE, "Error evaluating script "+functionScript+": "+e.getMessage());
                log.warn("Error while evaluating script {}: {}", functionScript, e.getMessage(), e);
            }    
        }
        
        return new ScriptAlgorithmExecutor(calg, (Invocable) scriptEngine, functionName, execCtx, eventProducer);
    }
    
    
    public static String generateFunctionCode(String functionName, CustomAlgorithm algorithmDef) {
        StringBuilder sb = new StringBuilder();
        
        String language = algorithmDef.getLanguage();
        if("JavaScript".equalsIgnoreCase(language)) {
            sb.append("function ").append(functionName);
        } else if("python".equalsIgnoreCase(language)) {
            sb.append("def ").append(functionName);
        } else {
            throw new IllegalArgumentException("Cannot execute scripts in "+language);
        }
        sb.append("(");
        
        boolean firstParam = true;
        for (InputParameter inputParameter : algorithmDef.getInputList()) {
            // Default-define all input values to null to prevent ugly runtime errors
            String argName = getArgName(inputParameter);
            if (firstParam) {
                firstParam = false;
            } else {
                sb.append(", ");
            }
            sb.append(argName);
        }

        // Set empty output bindings so that algorithms can write their attributes
        for (OutputParameter outputParameter : algorithmDef.getOutputList()) {
            String scriptName = outputParameter.getOutputName();
            if (scriptName == null) {
                scriptName = outputParameter.getParameter().getName();
            }
            if (firstParam) {
                firstParam = false;
            } else {
                sb.append(", ");
            }
            sb.append(scriptName);
        }
        sb.append(")");
        
        if("JavaScript".equalsIgnoreCase(language)) {
            sb.append(" {\n");
        } else if("python".equalsIgnoreCase(language)) {
            sb.append(":\n");
        }
        

        String[] a = algorithmDef.getAlgorithmText().split("\\r?\\n");
        for (String l : a) {
            sb.append("    ").append(l).append("\n");
        }
        
        if("JavaScript".equalsIgnoreCase(language)) {
            sb.append("}");
        }
        return sb.toString();
    }


    static String getArgName(InputParameter inputParameter) {
        String r = inputParameter.getInputName();
        if (r == null) {
            r = inputParameter.getParameterInstance().getParameter().getName();
        }
        return r;
    }
}