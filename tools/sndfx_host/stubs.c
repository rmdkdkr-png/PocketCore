/* GL·로그 빈 함수 — 시험에서 안 불린다(GL 길은 안 돈다) */
#include <stdarg.h>
int __android_log_print(int prio, const char *tag, const char *fmt, ...) { (void)prio; (void)tag; (void)fmt; return 0; }
#define S(n) void n(void) {}
S(glActiveTexture) S(glAttachShader) S(glBindTexture) S(glClear) S(glClearColor) S(glCompileShader) S(glCreateProgram) S(glCreateShader)
S(glDeleteProgram) S(glDeleteShader) S(glDrawArrays) S(glEnableVertexAttribArray) S(glGenTextures) S(glGetAttribLocation) S(glGetProgramInfoLog)
S(glGetProgramiv) S(glGetShaderInfoLog) S(glGetShaderiv) S(glGetUniformLocation) S(glLinkProgram) S(glPixelStorei) S(glShaderSource)
S(glTexImage2D) S(glTexParameteri) S(glTexSubImage2D) S(glUniform1f) S(glUniform1i) S(glUniform2f) S(glUseProgram) S(glVertexAttribPointer) S(glViewport)
