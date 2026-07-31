/* Minimal win32 jni_md.h for cross-compiling the JNI library with mingw-w64.
 * Matches the ABI of the JDK's win32 header: only type widths and call/export
 * decorations, no JDK code. */
#ifndef _JAVASOFT_JNI_MD_H_
#define _JAVASOFT_JNI_MD_H_

#define JNIEXPORT __declspec(dllexport)
#define JNIIMPORT __declspec(dllimport)
#define JNICALL __stdcall

typedef long jint;
typedef long long jlong;
typedef signed char jbyte;

#endif /* _JAVASOFT_JNI_MD_H_ */
