#define _GNU_SOURCE

#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#ifndef MAIN_CLASS
#error MAIN_CLASS must be defined
#endif

#ifndef CLASSPATH_FILE
#error CLASSPATH_FILE must be defined
#endif

static void fail(const char *message) {
  fprintf(stderr, "semantic-scala launcher: %s: %s\n", message, strerror(errno));
  exit(126);
}

static char *join_path(const char *left, const char *right) {
  size_t length = strlen(left) + strlen(right) + 2;
  char *result = malloc(length);
  if (result == NULL) fail("out of memory");
  if (snprintf(result, length, "%s/%s", left, right) < 0) fail("path formatting failed");
  return result;
}

static char *package_root(void) {
  char executable[PATH_MAX + 1];
  ssize_t length = readlink("/proc/self/exe", executable, PATH_MAX);
  if (length < 0) fail("cannot resolve /proc/self/exe");
  if (length == PATH_MAX) {
    errno = ENAMETOOLONG;
    fail("launcher path is too long");
  }
  executable[length] = '\0';
  char *slash = strrchr(executable, '/');
  if (slash == NULL) {
    errno = EINVAL;
    fail("launcher path has no bin directory");
  }
  *slash = '\0';
  slash = strrchr(executable, '/');
  if (slash == NULL) {
    errno = EINVAL;
    fail("launcher path has no package root");
  }
  *slash = '\0';
  return strdup(executable);
}

static char *read_classpath(const char *root) {
  char *relative_file = strdup(CLASSPATH_FILE);
  if (relative_file == NULL) fail("out of memory");
  char *classpath_file = join_path(root, relative_file);
  free(relative_file);
  FILE *input = fopen(classpath_file, "r");
  if (input == NULL) fail("cannot open classpath file");

  char *classpath = NULL;
  size_t used = 0;
  char *line = NULL;
  size_t capacity = 0;
  while (getline(&line, &capacity, input) >= 0) {
    size_t line_length = strlen(line);
    while (line_length > 0 && (line[line_length - 1] == '\n' || line[line_length - 1] == '\r')) {
      line[--line_length] = '\0';
    }
    if (line_length == 0 || line[0] == '/' || strstr(line, "..") != NULL || strchr(line, ':') != NULL) {
      errno = EINVAL;
      fail("invalid package-relative classpath entry");
    }
    char *entry = join_path(root, line);
    size_t entry_length = strlen(entry);
    char *expanded = realloc(classpath, used + entry_length + (used == 0 ? 1 : 2));
    if (expanded == NULL) fail("out of memory");
    classpath = expanded;
    if (used != 0) classpath[used++] = ':';
    memcpy(classpath + used, entry, entry_length + 1);
    used += entry_length;
    free(entry);
  }
  if (ferror(input)) fail("cannot read classpath file");
  if (fclose(input) != 0) fail("cannot close classpath file");
  free(line);
  free(classpath_file);
  if (classpath == NULL) {
    errno = EINVAL;
    fail("classpath file is empty");
  }
  return classpath;
}

int main(int argc, char **argv) {
  char *root = package_root();
  char *java = join_path(root, "runtime/bin/java");
  char *classpath = read_classpath(root);

  char **java_argv = calloc((size_t)argc + 6, sizeof(char *));
  if (java_argv == NULL) fail("out of memory");
  java_argv[0] = java;
  java_argv[1] = "-XX:+PerfDisableSharedMem";
  java_argv[2] = "-cp";
  java_argv[3] = classpath;
  java_argv[4] = MAIN_CLASS;
  for (int index = 1; index < argc; index++) java_argv[index + 4] = argv[index];
  java_argv[argc + 4] = NULL;

  execv(java, java_argv);
  fail("cannot execute package-local Java runtime");
}
