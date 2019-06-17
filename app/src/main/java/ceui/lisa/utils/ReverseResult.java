package ceui.lisa.utils;

import android.os.Parcel;
import android.os.Parcelable;

import java.io.IOException;

import okhttp3.ResponseBody;
import retrofit2.Response;

//TODO 实现ConverterFactory
public class ReverseResult implements Parcelable {
    private String title;
    private String url;
    private String mime;
    private String encoding;
    private String responseBody;
    private String history_url;

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeString(title);
        out.writeString(url);
        out.writeString(mime);
        out.writeString(encoding);
        out.writeString(responseBody);
        out.writeString(history_url);
    }

    public static final Parcelable.Creator<ReverseResult> CREATOR = new Parcelable.Creator<ReverseResult>() {
        public ReverseResult createFromParcel(Parcel in) {
            return new ReverseResult(in);
        }

        public ReverseResult[] newArray(int size) {
            return new ReverseResult[size];
        }
    };

    private ReverseResult(Parcel in) {
        title = in.readString();
        url = in.readString();
        mime = in.readString();
        encoding = in.readString();
        responseBody = in.readString();
        history_url = in.readString();
    }

    public ReverseResult(Response<ResponseBody> response){
        try {

            title = "Result";
            url = response.raw().request().url().toString();
            mime = response.headers().get("Content-Type");
            encoding = response.headers().get("Transfer-Encoding");
            responseBody = (response.body().string());
            history_url = response.raw().request().url().host();

        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getMime() {
        return mime;
    }

    public void setMime(String mime) {
        this.mime = mime;
    }

    public String getEncoding() {
        return encoding;
    }

    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    public String getHistory_url() {
        return history_url;
    }

    public void setHistory_url(String history_url) {
        this.history_url = history_url;
    }
}