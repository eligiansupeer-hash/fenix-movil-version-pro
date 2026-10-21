/* ---------------------------------------------------------------------------
 * config.h mínimo para compilar talloc 2.4.x contra bionic (Android NDK).
 *
 * El sistema de build upstream de talloc (waf) genera un config.h con un
 * configure cruzado; en este proyecto el build es cross puro aarch64-android26
 * y solo se necesita talloc como librería estática para enlazar proot, así que
 * este header shim declara los rasgos de libc/bionic que lib/replace/replace.h
 * y talloc.c consultan. Ninguna función se reimplementa: todos los símbolos
 * declarados existen en bionic (API 26).
 * Usado por tools/build-proot.sh; ver docs/build-proot.md.
 * ------------------------------------------------------------------------- */
#ifndef FENIX_TALLOC_BIONIC_CONFIG_H
#define FENIX_TALLOC_BIONIC_CONFIG_H

/* cabeceras estándar presentes en bionic */
#define HAVE_STDBOOL_H 1
#define HAVE_STDINT_H 1
#define HAVE_STDIO_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STDARG_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_LIMITS_H 1
#define HAVE_UNISTD_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_TIME_H 1
#define HAVE_SYS_WAIT_H 1
#define HAVE_SYS_RESOURCE_H 1
#define HAVE_SYS_SELECT_H 1
#define HAVE_SYS_UIO_H 1
#define HAVE_SYS_PARAM_H 1
#define HAVE_SYS_AUXV_H 1
#define HAVE_FCNTL_H 1
#define HAVE_DIRENT_H 1
#define HAVE_DLFCN_H 1

/* tipos básicos */
#define HAVE_BOOL 1
#define HAVE_INTPTR_T 1
#define HAVE_UINTPTR_T 1
#define HAVE_PTRDIFF_T 1
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1

/* funciones de libc presentes en bionic (API 26) */
#define HAVE_DLOPEN 1
#define HAVE_USLEEP 1
#define HAVE_GETTIMEOFDAY 1
#define HAVE_CLOCK_GETTIME 1
#define HAVE_STRDUP 1
#define HAVE_STRNCPY 1
#define HAVE_STRNLEN 1
#define HAVE_STRNDUP 1
#define HAVE_STPNCPY 1
#define HAVE_STRCASECMP 1
#define HAVE_STRNCASECMP 1
#define HAVE_STRSTR 1
#define HAVE_STRSEP 1
#define HAVE_STRTOK_R 1
#define HAVE_STRCHR 1
#define HAVE_STRRCHR 1
#define HAVE_STRERROR 1
#define HAVE_MEMSET 1
#define HAVE_MEMCPY 1
#define HAVE_MEMMOVE 1
#define HAVE_MEMCMP 1
#define HAVE_MEMCHR 1
#define HAVE_BZERO 1
#define HAVE_BCMP 1
#define HAVE_INDEX 1
#define HAVE_RINDEX 1
#define HAVE_SNPRINTF 1
#define HAVE_VSNPRINTF 1
#define HAVE_C99_VSNPRINTF 1
#define HAVE_ASPRINTF 1
#define HAVE_VASPRINTF 1
#define HAVE_STRTOULL 1
#define HAVE_STRLCPY 1
#define HAVE_STRLCAT 1
#define HAVE_GETPWNAM 1
#define HAVE_GETPWUID 1
#define HAVE_GETGRNAM 1
#define HAVE_CHMOD 1
#define HAVE_FCHMOD 1
#define HAVE_MKSTEMP 1
#define HAVE_MKTEMP 1
#define HAVE_GETCWD 1
#define HAVE_CHDIR 1
#define HAVE_SETENV 1
#define HAVE_UNSETENV 1
#define HAVE_PUTENV 1
#define HAVE_GETENV 1
#define HAVE_INITGROUPS 1
#define HAVE_SETGROUPS 1
#define HAVE_GETAUXVAL 1

#endif /* FENIX_TALLOC_BIONIC_CONFIG_H */
