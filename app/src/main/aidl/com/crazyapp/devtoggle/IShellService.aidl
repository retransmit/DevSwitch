package com.crazyapp.devtoggle;

// Implemented by ShellService, which Shizuku runs with the shell uid.
interface IShellService {
    // Transaction code reserved by the Shizuku server for tearing the service down.
    void destroy() = 16777114;

    // Runs the command (argv form), returns its combined stdout/stderr, throws on a non-zero exit.
    String execute(in String[] command) = 1;
}
