#include <stdio.h>


void dump_size(FILE* file, int value) {
    fprintf(file, "%d", value);
}

void dump_void_ptr(FILE* file, void* ptr) {
    fprintf(file, "%x\n",  ptr);
}

void fdump_char(FILE* file, char c) {
    fprintf(file, "%d\n",  c);
}

FILE* get_stderr() {
    return stderr;
}

FILE* get_stdout() {
    return stdout;
}

FILE* get_stdin() {
    return stdin;
}