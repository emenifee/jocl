package com.jogamp.opencl;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opencl.CLDevice.Type;
import com.jogamp.opencl.CLSampler.AddressingMode;
import com.jogamp.opencl.CLSampler.FilteringMode;
import com.jogamp.common.nio.PointerBuffer;
import com.jogamp.opencl.impl.CLImageFormatImpl;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static java.lang.System.*;
import static com.jogamp.opencl.CLException.*;
import static com.jogamp.common.nio.Buffers.*;
import static com.jogamp.common.os.Platform.*;
import static com.jogamp.opencl.CL.*;
import static com.jogamp.opencl.CLBuffer.*;

/**
 * CLContext is responsible for managing objects such as command-queues, memory,
 * program and kernel objects and for executing kernels on one or more devices
 * specified in the context.
 * @author Michael Bien
 */
public class CLContext extends CLObject implements CLResource {

    protected CLDevice[] devices;

    protected final List<CLProgram> programs;
    protected final List<CLSampler> samplers;
    protected final List<CLMemory<? extends Buffer>> memoryObjects;
    
    protected final Map<CLDevice, List<CLCommandQueue>> queuesMap;

    protected final CLPlatform platform;
    
    private final ErrorDispatcher errorHandler;

    protected CLContext(CLPlatform platform, long contextID, ErrorDispatcher dispatcher) {
        super(CLPlatform.getLowLevelCLInterface(), contextID);
        this.platform = platform;
        this.programs = new ArrayList<CLProgram>();
        this.samplers = new ArrayList<CLSampler>();
        this.memoryObjects = new ArrayList<CLMemory<? extends Buffer>>();
        this.queuesMap = new HashMap<CLDevice, List<CLCommandQueue>>();
        this.errorHandler = dispatcher;

        /*
        addCLErrorHandler(new CLErrorHandler() {
            public void onError(String errinfo, ByteBuffer private_info, long cb) {
                java.util.logging.Logger.getLogger(getClass().getName()).warning(errinfo);
            }
        });
        */
        
    }

    private void initDevices() {
        
        if (devices == null) {

            PointerBuffer deviceCount = PointerBuffer.allocateDirect(1);

            int ret = cl.clGetContextInfo(ID, CL.CL_CONTEXT_DEVICES, 0, null, deviceCount);
            checkForError(ret, "can not enumerate devices");

            ByteBuffer deviceIDs = Buffers.newDirectByteBuffer((int)deviceCount.get());
            ret = cl.clGetContextInfo(ID, CL.CL_CONTEXT_DEVICES, deviceIDs.capacity(), deviceIDs, null);
            checkForError(ret, "can not enumerate devices");

            devices = new CLDevice[deviceIDs.capacity() / (is32Bit() ? 4 : 8)];
            for (int i = 0; i < devices.length; i++) {
                devices[i] = new CLDevice(this, is32Bit() ? deviceIDs.getInt() : deviceIDs.getLong());
            }
        }
    }

    /**
     * Creates a context on all available devices (CL_DEVICE_TYPE_ALL).
     * The platform to be used is implementation dependent.
     */
    public static CLContext create() {
        return create((CLPlatform)null, Type.ALL);
    }

    /**
     * Creates a context on the specified device types.
     * The platform to be used is implementation dependent.
     */
    public static CLContext create(Type... deviceTypes) {
        return create(null, deviceTypes);
    }

    /**
     * Creates a context on the specified devices.
     * The platform to be used is implementation dependent.
     */
    public static CLContext create(CLDevice... devices) {
        return create(null, devices);
    }

    /**
     * Creates a context on the specified platform on all available devices (CL_DEVICE_TYPE_ALL).
     */
    public static CLContext create(CLPlatform platform) {
        return create(platform, Type.ALL);
    }

    /**
     * Creates a context on the specified platform and with the specified
     * device types.
     */
    public static CLContext create(CLPlatform platform, Type... deviceTypes) {

        if(platform == null) {
            platform = CLPlatform.getDefault();
        }

        long type = toDeviceBitmap(deviceTypes);

        PointerBuffer properties = setupContextProperties(platform);
        ErrorDispatcher dispatcher = new ErrorDispatcher();
        return new CLContext(platform, createContextFromType(dispatcher, properties, type), dispatcher);
    }

    /**
     * Creates a context on the specified platform and with the specified
     * devices.
     */
    public static CLContext create(CLPlatform platform, CLDevice... devices) {

        if(platform == null) {
            platform = CLPlatform.getDefault();
        }

        PointerBuffer properties = setupContextProperties(platform);
        ErrorDispatcher dispatcher = new ErrorDispatcher();
        CLContext context = new CLContext(platform, createContext(dispatcher, properties, devices), dispatcher);
        if(devices != null) {
            for (int i = 0; i < devices.length; i++) {
                devices[i].setContext(context);
            }
        }
        return context;
    }

    protected static long createContextFromType(CLErrorHandler handler, PointerBuffer properties, long deviceType) {

        IntBuffer status = newDirectIntBuffer(1);
        long context = CLPlatform.getLowLevelCLInterface().clCreateContextFromType(properties, deviceType, handler, status);

        checkForError(status.get(), "can not create CL context");

        return context;
    }

    protected static long createContext(CLErrorHandler handler, PointerBuffer properties, CLDevice... devices) {

        IntBuffer status = newDirectIntBuffer(1);
        PointerBuffer pb = null;
        if(devices != null && devices.length != 0) {
            pb = PointerBuffer.allocateDirect(devices.length);
            for (int i = 0; i < devices.length; i++) {
                CLDevice device = devices[i];
                if(device == null) {
                    throw new IllegalArgumentException("device at index "+i+" was null.");
                }
                pb.put(i, device.ID);
            }
        }
        long context = CLPlatform.getLowLevelCLInterface().clCreateContext(properties, pb, handler, status);

        checkForError(status.get(), "can not create CL context");

        return context;
    }

    private static PointerBuffer setupContextProperties(CLPlatform platform) {

        if(platform == null) {
            throw new RuntimeException("no OpenCL installation found");
        }

        return (PointerBuffer)PointerBuffer.allocateDirect(3).put(CL.CL_CONTEXT_PLATFORM)
                                              .put(platform.ID).put(0) // 0 terminated array
                                              .rewind();
    }

    /**
     * Creates a program from the given sources, the program is not build yet.
     */
    public CLProgram createProgram(String src) {
        CLProgram program = CLProgram.create(this, src);
        programs.add(program);
        return program;
    }

    /**
     * Creates a program and reads the source from stream, the program is not build yet.
     * @throws IOException when a IOException occurred while reading or closing the stream.
     */
    public CLProgram createProgram(InputStream source) throws IOException {

        if(source == null)
            throw new IllegalArgumentException("input stream for program source must not be null");

        BufferedReader reader = new BufferedReader(new InputStreamReader(source));
        StringBuilder sb = new StringBuilder();

        String line;
        try {
            while ((line = reader.readLine()) != null)
                sb.append(line).append("\n");
        } finally {
            source.close();
        }

        return createProgram(sb.toString());
    }

    /**
     * Creates a program from the given binaries, the program is not build yet.
     * <br/>Creating a program will fail if:<br/>
     * <ul>
     * <li>the submitted binaries are invalid or can not be loaded from the OpenCL driver</li>
     * <li>the binaries do not fit to the CLDevices associated with this context</li>
     * <li>binaries are missing for one or more CLDevices</li>
     * </ul>
     */
    public CLProgram createProgram(Map<CLDevice, byte[]> binaries) {
        CLProgram program = CLProgram.create(this, binaries);
        programs.add(program);
        return program;
    }

    /**
     * Creates a CLBuffer with the specified flags and element count. No flags creates a MEM.READ_WRITE buffer.
     */
    public final CLBuffer<ShortBuffer> createShortBuffer(int size, Mem... flags) {
        return createBuffer(newDirectShortBuffer(size), flags);
    }

    /**
     * Creates a CLBuffer with the specified flags and element count. No flags creates a MEM.READ_WRITE buffer.
     */
    public final CLBuffer<IntBuffer> createIntBuffer(int size, Mem... flags) {
        return createBuffer(newDirectIntBuffer(size), flags);
    }

    /**
     * Creates a CLBuffer with the specified flags and element count. No flags creates a MEM.READ_WRITE buffer.
     */
    public final CLBuffer<LongBuffer> createLongBuffer(int size, Mem... flags) {
        return createBuffer(newDirectLongBuffer(size), flags);
    }

    /**
     * Creates a CLBuffer with the specified flags and element count. No flags creates a MEM.READ_WRITE buffer.
     */
    public final CLBuffer<FloatBuffer> createFloatBuffer(int size, Mem... flags) {
        return createBuffer(newDirectFloatBuffer(size), flags);
    }

    /**
     * Creates a CLBuffer with the specified flags and element count. No flags creates a MEM.READ_WRITE buffer.
     */
    public final CLBuffer<DoubleBuffer> createDoubleBuffer(int size, Mem... flags) {
        return createBuffer(newDirectDoubleBuffer(size), flags);
    }

    /**
     * Creates a CLBuffer with the specified flags and buffer size in bytes. No flags creates a MEM.READ_WRITE buffer.
     */
    public final CLBuffer<ByteBuffer> createByteBuffer(int size, Mem... flags) {
        return createByteBuffer(size, Mem.flagsToInt(flags));
    }

    /**
     * Creates a CLBuffer with the specified flags and buffer size in bytes.
     */
    public final CLBuffer<ByteBuffer> createByteBuffer(int size, int flags) {
        return createBuffer(newDirectByteBuffer(size), flags);
    }

    /**
     * Creates a CLBuffer with the specified flags. No flags creates a MEM.READ_WRITE buffer.
     */
    public final CLBuffer<?> createBuffer(int size, Mem... flags) {
        return createBuffer(size, Mem.flagsToInt(flags));
    }

    /**
     * Creates a CLBuffer with the specified flags.
     */
    public final CLBuffer<?> createBuffer(int size, int flags) {
        CLBuffer<?> buffer = CLBuffer.create(this, size, flags);
        memoryObjects.add(buffer);
        return buffer;
    }

    /**
     * Creates a CLBuffer with the specified flags. No flags creates a MEM.READ_WRITE buffer.
     */
    public final <B extends Buffer> CLBuffer<B> createBuffer(B directBuffer, Mem... flags) {
        return createBuffer(directBuffer, Mem.flagsToInt(flags));
    }

    /**
     * Creates a CLBuffer with the specified flags.
     */
    public final <B extends Buffer> CLBuffer<B> createBuffer(B directBuffer, int flags) {
        CLBuffer<B> buffer = CLBuffer.create(this, directBuffer, flags);
        memoryObjects.add(buffer);
        return buffer;
    }

    /**
     * Creates a CLImage2d with the specified format, dimension and flags.
     */
    public final CLImage2d<?> createImage2d(int width, int height, CLImageFormat format, Mem... flags) {
        return createImage2d(null, width, height, 0, format, flags);
    }

    /**
     * Creates a CLImage2d with the specified format, dimension and flags.
     */
    public final CLImage2d<?> createImage2d(int width, int height, int rowPitch, CLImageFormat format, Mem... flags) {
        return createImage2d(null, width, height, rowPitch, format, flags);
    }

    /**
     * Creates a CLImage2d with the specified format, dimension and flags.
     */
    public final <B extends Buffer> CLImage2d<B> createImage2d(B directBuffer, int width, int height, CLImageFormat format, Mem... flags) {
        return createImage2d(directBuffer, width, height, 0, format, flags);
    }

    /**
     * Creates a CLImage2d with the specified format, dimension and flags.
     */
    public final <B extends Buffer> CLImage2d<B> createImage2d(B directBuffer, int width, int height, int rowPitch, CLImageFormat format, Mem... flags) {
        CLImage2d<B> image = CLImage2d.createImage(this, directBuffer, width, height, rowPitch, format, Mem.flagsToInt(flags));
        memoryObjects.add(image);
        return image;
    }

    /**
     * Creates a CLImage3d with the specified format, dimension and flags.
     */
    public final CLImage3d<?> createImage3d(int width, int height, CLImageFormat format, Mem... flags) {
        return createImage3d(null, width, height, 0, format, flags);
    }

    /**
     * Creates a CLImage3d with the specified format, dimension and flags.
     */
    public final CLImage3d<?> createImage3d(int width, int height, int depth, int rowPitch, CLImageFormat format, Mem... flags) {
        return createImage3d(null, width, height, rowPitch, format, flags);
    }

    /**
     * Creates a CLImage3d with the specified format, dimension and flags.
     */
    public final <B extends Buffer> CLImage3d<B> createImage3d(B directBuffer, int width, int height, int depth, CLImageFormat format, Mem... flags) {
        return createImage3d(directBuffer, width, height, depth, 0, 0, format, flags);
    }

    /**
     * Creates a CLImage3d with the specified format, dimension and flags.
     */
    public final <B extends Buffer> CLImage3d<B> createImage3d(B directBuffer, int width, int height, int depth, int rowPitch, int slicePitch, CLImageFormat format, Mem... flags) {
        CLImage3d<B> image = CLImage3d.createImage(this, directBuffer, width, height, depth, rowPitch, slicePitch, format, Mem.flagsToInt(flags));
        memoryObjects.add(image);
        return image;
    }

    CLCommandQueue createCommandQueue(CLDevice device, long properties) {

        CLCommandQueue queue = CLCommandQueue.create(this, device, properties);

        List<CLCommandQueue> list = queuesMap.get(device);
        if(list == null) {
            list = new ArrayList<CLCommandQueue>();
            queuesMap.put(device, list);
        }
        list.add(queue);

        return queue;
    }

    public CLSampler createSampler(AddressingMode addrMode, FilteringMode filtMode, boolean normalizedCoords) {
        CLSampler sampler = CLSampler.create(this, addrMode, filtMode, normalizedCoords);
        samplers.add(sampler);
        return sampler;
    }

    void onProgramReleased(CLProgram program) {
        programs.remove(program);
    }

    void onMemoryReleased(CLMemory<?> buffer) {
        memoryObjects.remove(buffer);
    }

    void onCommandQueueReleased(CLDevice device, CLCommandQueue queue) {
        List<CLCommandQueue> list = queuesMap.get(device);
        list.remove(queue);
        // remove empty lists from map
        if(list.isEmpty())
            queuesMap.remove(device);
    }

    void onSamplerReleased(CLSampler sampler) {
        samplers.remove(sampler);
    }

    public void addCLErrorHandler(CLErrorHandler handler) {
        errorHandler.addHandler(handler);
    }

    public void removeCLErrorHandler(CLErrorHandler handler) {
        errorHandler.removeHandler(handler);
    }

    /**
     * Releases the context and all resources.
     */
    public void release() {

        try{
            //release all resources
            while(!programs.isEmpty())
                programs.get(0).release();

            while(!memoryObjects.isEmpty())
                memoryObjects.get(0).release();

            while(!samplers.isEmpty())
                samplers.get(0).release();

            for (CLDevice device : getDevices()) {
                List<CLCommandQueue> list = queuesMap.get(device);
                if(list != null) {
                    while(!list.isEmpty()) {
                        list.get(0).release();
                    }
                }
            }

        }finally{
            int ret = cl.clReleaseContext(ID);
            checkForError(ret, "error releasing context");
        }

    }

    protected void overrideContext(CLDevice device) {
        device.setContext(this);
    }

    private CLImageFormat[] getSupportedImageFormats(int flags, int type) {

        int[] entries = new int[1];
        int ret = cl.clGetSupportedImageFormats(ID, flags, type, 0, null, entries, 0);
        if(ret != CL_SUCCESS) {
            throw newException(ret, "error calling clGetSupportedImageFormats");
        }

        int count = entries[0];
        if(count == 0) {
            return new CLImageFormat[0];
        }

        CLImageFormat[] formats = new CLImageFormat[count];
        CLImageFormatImpl impl = CLImageFormatImpl.create(newDirectByteBuffer(count * CLImageFormatImpl.size()));
        ret = cl.clGetSupportedImageFormats(ID, flags, type, count, impl, null, 0);
        if(ret != CL_SUCCESS) {
            throw newException(ret, "error calling clGetSupportedImageFormats");
        }

        ByteBuffer buffer = impl.getBuffer();
        for (int i = 0; i < formats.length; i++) {
            formats[i] = new CLImageFormat(CLImageFormatImpl.create(buffer.slice()));
            buffer.position(i*CLImageFormatImpl.size());
        }

        return formats;

    }

    /**
     * Returns all supported 2d image formats with the (optional) memory allocation flags.
     */
    public CLImageFormat[] getSupportedImage2dFormats(Mem... flags) {
        return getSupportedImageFormats(flags==null?0:Mem.flagsToInt(flags), CL_MEM_OBJECT_IMAGE2D);
    }

    /**
     * Returns all supported 3d image formats with the (optional) memory allocation flags.
     */
    public CLImageFormat[] getSupportedImage3dFormats(Mem... flags) {
        return getSupportedImageFormats(flags==null?0:Mem.flagsToInt(flags), CL_MEM_OBJECT_IMAGE3D);
    }

    /**
     * Returns the CLPlatform this context is running on.
     */
    @Override
    public CLPlatform getPlatform() {
        return platform;
    }

    @Override
    public CLContext getContext() {
        return this;
    }

    /**
     * Returns a read only view of all programs associated with this context.
     */
    public List<CLProgram> getPrograms() {
        return Collections.unmodifiableList(programs);
    }

    /**
     * Returns a read only view of all allocated memory objects associated with this context.
     */
    public List<CLMemory<? extends Buffer>> getMemoryObjects() {
        return Collections.unmodifiableList(memoryObjects);
    }

    /**
     * Returns a read only view of all samplers associated with this context.
     */
    public List<CLSampler> getSamplers() {
        return Collections.unmodifiableList(samplers);
    }

    /**
     * Returns the device with maximal FLOPS from this context.
     * The device speed is estimated by calculating the product of
     * MAX_COMPUTE_UNITS and MAX_CLOCK_FREQUENCY.
     * @see #getMaxFlopsDevice(com.jogamp.opencl.CLDevice.Type)
     */
    public CLDevice getMaxFlopsDevice() {
        return CLPlatform.findMaxFlopsDevice(getDevices());
    }

    /**
     * Returns the device with maximal FLOPS of the specified device type from this context.
     * The device speed is estimated by calculating the product of
     * MAX_COMPUTE_UNITS and MAX_CLOCK_FREQUENCY.
     */
    public CLDevice getMaxFlopsDevice(CLDevice.Type type) {
        return CLPlatform.findMaxFlopsDevice(getDevices(), type);
    }

    /**
     * Returns all devices associated with this CLContext.
     */
    public CLDevice[] getDevices() {
        initDevices();
        return devices;
    }

    /**
     * Return the low level OpenCL interface.
     */
    public CL getCL() {
        return cl;
    }

    CLDevice getDevice(long dID) {
        CLDevice[] deviceArray = getDevices();
        for (int i = 0; i < deviceArray.length; i++) {
            if(dID == deviceArray[i].ID)
                return deviceArray[i];
        }
        return null;
    }

    protected static long toDeviceBitmap(Type[] deviceTypes) {
        long bitmap = 0;
        if (deviceTypes != null) {
            for (int i = 0; i < deviceTypes.length; i++) {
                Type type = deviceTypes[i];
                if(type == null) {
                    throw new IllegalArgumentException("Device type at index "+i+" was null.");
                }
                bitmap |= type.TYPE;
            }
        }
        return bitmap;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()+" [id: " + ID
                                          + " #devices: " + getDevices().length
                                          + "]";
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final CLContext other = (CLContext) obj;
        if (this.ID != other.ID) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 23 * hash + (int) (this.ID ^ (this.ID >>> 32));
        return hash;
    }

    protected static ErrorDispatcher createErrorHandler() {
        return new ErrorDispatcher();
    }

    protected static class ErrorDispatcher implements CLErrorHandler {

        private volatile CLErrorHandler[] clientHandlers = new CLErrorHandler[0];

        public void onError(String errinfo, ByteBuffer private_info, long cb) {
            CLErrorHandler[] handlers = this.clientHandlers;
            for (int i = 0; i < handlers.length; i++) {
                handlers[i].onError(errinfo, private_info, cb);
            }
        }

        private void addHandler(CLErrorHandler handler) {

            if(handler == null) {
                throw new IllegalArgumentException("handler was null.");
            }

            CLErrorHandler[] handlers = new CLErrorHandler[clientHandlers.length+1];
            arraycopy(clientHandlers, 0, handlers, 0, clientHandlers.length);
            handlers[handlers.length-1] = handler;
            clientHandlers = handlers;
        }

        private void removeHandler(CLErrorHandler handler) {
            
            if(handler == null) {
                throw new IllegalArgumentException("handler was null.");
            }

            for (int i = 0; i < clientHandlers.length; i++) {
                if(handler.equals(clientHandlers[i])) {
                    CLErrorHandler[] handlers = new CLErrorHandler[clientHandlers.length-1];
                    arraycopy(clientHandlers, 0, handlers, 0, i);
                    arraycopy(clientHandlers, i, handlers, 0, handlers.length-i);
                    clientHandlers = handlers;
                    return;
                }
            }
        }


    }


}
