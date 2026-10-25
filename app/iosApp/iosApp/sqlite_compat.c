#include <stddef.h>
#include <dlfcn.h>
#include <stdarg.h>

int sqlite3_load_extension(void *db, const char *zFile, const char *zProc, char **pzErrMsg) {
    if (pzErrMsg) {
        *pzErrMsg = "sqlite3_load_extension is not supported on this platform";
    }
    return 1;
}

int sqlite3_db_config(void *db, int op, ...) {
    if (op == 1005) { // SQLITE_DBCONFIG_ENABLE_LOAD_EXTENSION
        return 0; // SQLITE_OK
    }
    static int (*real_db_config)(void *, int, ...) = NULL;
    if (!real_db_config) {
        real_db_config = (int (*)(void *, int, ...))dlsym(RTLD_DEFAULT, "sqlite3_db_config");
    }
    if (real_db_config) {
        va_list args;
        va_start(args, op);
        void *p1 = va_arg(args, void *);
        void *p2 = va_arg(args, void *);
        va_end(args);
        return real_db_config(db, op, p1, p2);
    }
    return 0;
}
