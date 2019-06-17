//package ceui.lisa.utils;
//
//import java.io.IOException;
//import java.lang.annotation.Annotation;
//import java.lang.reflect.Type;
//
//import okhttp3.RequestBody;
//import okhttp3.ResponseBody;
//import retrofit2.Converter;
//import retrofit2.Retrofit;
//
////ResponseBody不足以构造ReverseResult
//public class ReverseResultConverterFactory extends Converter.Factory {
//
//
//
//    @Override
//    public Converter<ResponseBody, ?> responseBodyConverter(Type type, Annotation[] annotations, Retrofit retrofit) {
//        return new Converter<ResponseBody, ReverseResult>() {
//            @Override
//            public ReverseResult convert(ResponseBody responseBody) throws IOException {
//                return new ReverseResult(responseBody);
//            }
//        };
//    }
//
//    @Override public Converter<?, RequestBody> requestBodyConverter(Type type, Annotation[] parameterAnnotations,
//                                                                    Annotation[] methodAnnotations, Retrofit retrofit) {
//        return new Converter<ResponseBody, ReverseResult>() {
//            @Override
//            public ReverseResult convert(ResponseBody responseBody) throws IOException {
//                return new ReverseResult(responseBody);
//            }
//        };
//    }
//}
