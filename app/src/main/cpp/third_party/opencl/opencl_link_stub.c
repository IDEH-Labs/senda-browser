/*
 * Biblioteca solo para ENLAZAR libggml-opencl.so (no se empaqueta en el APK: ver packaging en build.gradle).
 * En el teléfono se usa el libOpenCL.so del fabricante (/vendor/lib64), declarado en public.libraries.txt.
 * Solo los nombres importan: son las 33 funciones OpenCL <= 2.0 que usa ggml-opencl (GGML_OPENCL_TARGET_VERSION=200).
 * Si una actualización de llama.cpp usa otra, el enlace falla con «undefined symbol» y hay que añadirla aquí.
 */
void clBuildProgram(void) {}
void clCreateBuffer(void) {}
void clCreateCommandQueue(void) {}
void clCreateContext(void) {}
void clCreateImage(void) {}
void clCreateKernel(void) {}
void clCreateProgramWithBinary(void) {}
void clCreateProgramWithSource(void) {}
void clCreateSubBuffer(void) {}
void clEnqueueBarrierWithWaitList(void) {}
void clEnqueueCopyBuffer(void) {}
void clEnqueueFillBuffer(void) {}
void clEnqueueMapBuffer(void) {}
void clEnqueueMarkerWithWaitList(void) {}
void clEnqueueNDRangeKernel(void) {}
void clEnqueueReadBuffer(void) {}
void clEnqueueUnmapMemObject(void) {}
void clEnqueueWriteBuffer(void) {}
void clFinish(void) {}
void clFlush(void) {}
void clGetDeviceIDs(void) {}
void clGetDeviceInfo(void) {}
void clGetKernelWorkGroupInfo(void) {}
void clGetPlatformIDs(void) {}
void clGetPlatformInfo(void) {}
void clGetProgramBuildInfo(void) {}
void clGetProgramInfo(void) {}
void clReleaseEvent(void) {}
void clReleaseKernel(void) {}
void clReleaseMemObject(void) {}
void clReleaseProgram(void) {}
void clSetKernelArg(void) {}
void clWaitForEvents(void) {}
