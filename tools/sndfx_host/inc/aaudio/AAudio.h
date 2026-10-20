/* 시험용 AAudio (호스트) — 콜백 스레드를 흉내 낸다 */
#include <stdint.h>
typedef struct AAudioStreamStruct AAudioStream;
typedef struct AAudioStreamBuilderStruct AAudioStreamBuilder;
typedef int aaudio_result_t; typedef int aaudio_data_callback_result_t; typedef int aaudio_format_t;
enum { AAUDIO_CALLBACK_RESULT_CONTINUE, AAUDIO_ERROR_DISCONNECTED=-899, AAUDIO_FORMAT_PCM_I16=1, AAUDIO_OK=0, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY=12 };
typedef aaudio_data_callback_result_t (*AAudioStream_dataCallback)(AAudioStream *, void *, void *, int32_t);
typedef void (*AAudioStream_errorCallback)(AAudioStream *, void *, aaudio_result_t);
aaudio_result_t AAudio_createStreamBuilder(AAudioStreamBuilder **b);
aaudio_result_t AAudioStreamBuilder_delete(AAudioStreamBuilder *b);
aaudio_result_t AAudioStreamBuilder_openStream(AAudioStreamBuilder *b, AAudioStream **s);
void AAudioStreamBuilder_setChannelCount(AAudioStreamBuilder *b, int32_t n);
void AAudioStreamBuilder_setDataCallback(AAudioStreamBuilder *b, AAudioStream_dataCallback cb, void *ud);
void AAudioStreamBuilder_setErrorCallback(AAudioStreamBuilder *b, AAudioStream_errorCallback cb, void *ud);
void AAudioStreamBuilder_setFormat(AAudioStreamBuilder *b, aaudio_format_t f);
void AAudioStreamBuilder_setPerformanceMode(AAudioStreamBuilder *b, int32_t m);
void AAudioStreamBuilder_setSampleRate(AAudioStreamBuilder *b, int32_t r);
aaudio_result_t AAudioStream_close(AAudioStream *s);
aaudio_result_t AAudioStream_requestStart(AAudioStream *s);
aaudio_result_t AAudioStream_requestStop(AAudioStream *s);
