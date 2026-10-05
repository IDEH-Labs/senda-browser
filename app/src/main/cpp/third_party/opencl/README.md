# OpenCL para la GPU (ggml-opencl)

- `CL/`: cabeceras oficiales de Khronos, https://github.com/KhronosGroup/OpenCL-Headers
  commit 30bc20a8e90468e231d7c639805ae61ad1fefa4f, licencia Apache 2.0 (`LICENSE`).
- `opencl_link_stub.c`: biblioteca vacía con los nombres de las funciones que usa ggml-opencl, solo para
  enlazar. No se incluye en el APK: cada teléfono usa el OpenCL de su fabricante, y si no lo tiene
  (o no lo expone a las apps) ggml simplemente no carga el módulo de GPU.
