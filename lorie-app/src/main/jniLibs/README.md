`arm64-v8a/libburrow.so` is Burrow, the container runtime of XDesk's built-in Linux (GPL-3.0), built from
https://github.com/n2ray/burrow at commit 8705740 (branch `dir-cache`) with `make` (clang and lld, aarch64).
It is a program, not a library: the `lib*.so` name puts it in the app's native library directory, the one place
an app may run a program from.
