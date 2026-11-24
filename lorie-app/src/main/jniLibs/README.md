`arm64-v8a/libburrow.so` is Burrow, the container runtime of XDesk's built-in Linux (GPL-3.0), built from
https://github.com/n2ray/burrow/commit/84cbf9b9c6f6c82ff7e3aca465533fc8c7ede8b7 with `make` (clang 23.1.2 and LLD 22.1.8, aarch64).
It is a program, not a library: the `lib*.so` name puts it in the app's native library directory, the one place
an app may run a program from.
