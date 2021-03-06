package com.jogamp.opencl.util;

import com.jogamp.opencl.CLDevice;
import com.jogamp.opencl.CLProgram;
import java.util.Map;

/**
 * Configuration representing everything needed to build an OpenCL program (program included).
 * CLProgramConfiguration is a helper for building programs with more complex configurations or
 * building multiple programs with the similar configuration.
 * @see CLProgram#prepare()
 * @see com.jogamp.opencl.CLProgramBuilder#createConfiguration(com.jogamp.opencl.CLProgram)
 * @see com.jogamp.opencl.CLProgramBuilder#loadConfiguration(java.io.ObjectInputStream, com.jogamp.opencl.CLContext)
 * @author Michael Bien
 */
public interface CLProgramConfiguration extends CLBuildConfiguration {

    /**
     * Builds or rebuilds a program.
     */
    public CLProgram build();

    /**
     * Builds or rebuilds a program.
     * @param listener The callback who will be notified when the program has built.
     */
    public CLProgram build(CLBuildListener listener);

    /**
     * Returns the program.
     */
    public CLProgram getProgram();

    /**
     * Returns a new instance of of this configuration without a {@link CLProgram},
     * program binaries or sources associated with it.
     */
    public CLBuildConfiguration asBuildConfiguration();


    // overwrite with CLProgramConfiguration as return type
    @Override public CLProgramConfiguration forDevice(CLDevice device);
    @Override public CLProgramConfiguration forDevices(CLDevice... devices);
    @Override public CLProgramConfiguration withDefine(String name);
    @Override public CLProgramConfiguration withDefine(String name, Object value);
    @Override public CLProgramConfiguration withDefines(String... names);
    @Override public CLProgramConfiguration withDefines(Map<String, ? extends Object> defines);
    @Override public CLProgramConfiguration withOption(String option);
    @Override public CLProgramConfiguration withOptions(String... options);
    @Override public CLProgramConfiguration reset();
    @Override public CLProgramConfiguration resetOptions();
    @Override public CLProgramConfiguration resetDefines();
    @Override public CLProgramConfiguration resetDevices();
    @Override public CLProgramConfiguration clone();

}
