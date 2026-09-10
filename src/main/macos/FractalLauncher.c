#include <jni.h>
#include <stddef.h>

/* OpenJDK's exported launcher ABI (java.h / jli_util.h). Keep parsing in the
 * selected JDK, including @argfiles, JDK_JAVA_OPTIONS and classpath wildcards.
 * Building with the local SDK enables AppKit's current native window design.
 */
typedef struct {
    char **elements;
    size_t size;
    size_t capacity;
} *ArgumentList;

extern ArgumentList JLI_List_new(size_t);
extern void JLI_List_add(ArgumentList, char *);
extern char *JLI_StringDup(const char *);
extern void JLI_MemFree(void *);
extern void JLI_InitArgProcessing(jboolean, jboolean);
extern jboolean JLI_AddArgsFromEnvVar(ArgumentList, const char *);
extern ArgumentList JLI_PreprocessArg(const char *, jboolean);
extern int JLI_Launch(int, char **, int, const char **, int, const char **,
                      const char *, const char *, const char *, const char *,
                      jboolean, jboolean, jboolean, jint);

/* JLI resolves main again when it hands the first thread to Cocoa. */
JNIEXPORT int main(int argc, char **argv) {
    JLI_InitArgProcessing(JNI_FALSE, JNI_FALSE);
    ArgumentList arguments = JLI_List_new((size_t)argc + 1);
    JLI_List_add(arguments, JLI_StringDup(argv[0]));
    JLI_AddArgsFromEnvVar(arguments, "JDK_JAVA_OPTIONS");
    for (int i = 1; i < argc; ++i) {
        ArgumentList expanded = JLI_PreprocessArg(argv[i], JNI_TRUE);
        if (expanded == NULL) {
            JLI_List_add(arguments, JLI_StringDup(argv[i]));
        } else {
            for (size_t j = 0; j < expanded->size; ++j) {
                JLI_List_add(arguments, expanded->elements[j]);
            }
            JLI_MemFree(expanded->elements);
            JLI_MemFree(expanded);
        }
    }
    int count = (int)arguments->size;
    JLI_List_add(arguments, NULL);
    return JLI_Launch(count, arguments->elements, 0, NULL, 0, NULL,
                      FRACTAL_JAVA_VERSION, "0.0", "FractalUI", "java",
                      JNI_FALSE, JNI_TRUE, JNI_FALSE, 0);
}
