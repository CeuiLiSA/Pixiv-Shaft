/*
 * Copyright 2017 JessYan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package me.jessyan.progressmanager.body;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * ================================================
 * {@link ProgressInfo} 用于存储与进度有关的变量,已实现 {@link Parcelable}
 * <p>
 * Created by JessYan on 07/06/2017 12:09
 * <a href="mailto:jess.yan.effort@gmail.com">Contact me</a>
 * <a href="https://github.com/JessYanCoding">Follow me</a>
 * ================================================
 */
public class ProgressInfo implements Parcelable {
    private long currentBytes; //当前已上传或下载的总长度
    private long contentLength; //数据总长度
    private long intervalTime; //本次调用距离上一次被调用所间隔的时间(毫秒)
    private long eachBytes; //本次调用距离上一次被调用的间隔时间内上传或下载的byte长度
    private long id; //如果同一个 Url 地址,上一次的上传或下载操作都还没结束,
    //又开始了新的上传或下载操作(比如用户多次点击上传或下载同一个 Url 地址,当然你也可以在上层屏蔽掉用户的重复点击),
    //此 id (请求开始时的时间)就变得尤为重要,用来区分正在执行的进度信息,因为是以请求开始时的时间作为 id ,所以值越大,说明该请求越新
    private boolean finish; //进度是否完成


    public ProgressInfo(long id) {
        this.id = id;
    }

    void setCurrentbytes(long currentbytes) {
        this.currentBytes = currentbytes;
    }

    void setContentLength(long contentLength) {
        this.contentLength = contentLength;
    }

    void setIntervalTime(long intervalTime) {
        this.intervalTime = intervalTime;
    }

    void setEachBytes(long eachBytes) {
        this.eachBytes = eachBytes;
    }

    void setFinish(boolean finish) {
        this.finish = finish;
    }

    public long getCurrentbytes() {
        return currentBytes;
    }

    public long getContentLength() {
        return contentLength;
    }

    public long getIntervalTime() {
        return intervalTime;
    }

    public long getEachBytes() {
        return eachBytes;
    }

    public long getId() {
        return id;
    }

    public boolean isFinish() {
        return finish;
    }

    /**
     * 获取百分比,该计算舍去了小数点,如果你想得到更精确的值,请自行计算
     *
     * @return
     */
    public int getPercent() {
        if (getCurrentbytes() <= 0 || getContentLength() <= 0) return 0;
        return (int) ((100 * getCurrentbytes()) / getContentLength());
    }

    /**
     * 获取上传或下载网络速度,单位为byte/s,如果你想得到更精确的值,请自行计算
     *
     * @return
     */
    public long getSpeed() {
        if (getEachBytes() <= 0 || getIntervalTime() <= 0) return 0;
        return getEachBytes() * 1000 / getIntervalTime();
    }

    @Override
    public String toString() {
        return "ProgressInfo{" +
                "id=" + id +
                ", currentBytes=" + currentBytes +
                ", contentLength=" + contentLength +
                ", eachBytes=" + eachBytes +
                ", intervalTime=" + intervalTime +
                ", finish=" + finish +
                '}';
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(this.currentBytes);
        dest.writeLong(this.contentLength);
        dest.writeLong(this.intervalTime);
        dest.writeLong(this.eachBytes);
        dest.writeLong(this.id);
        dest.writeByte(this.finish ? (byte) 1 : (byte) 0);
    }

    protected ProgressInfo(Parcel in) {
        this.currentBytes = in.readLong();
        this.contentLength = in.readLong();
        this.intervalTime = in.readLong();
        this.eachBytes = in.readLong();
        this.id = in.readLong();
        this.finish = in.readByte() != 0;
    }

    public static final Creator<ProgressInfo> CREATOR = new Creator<ProgressInfo>() {
        @Override
        public ProgressInfo createFromParcel(Parcel source) {
            return new ProgressInfo(source);
        }

        @Override
        public ProgressInfo[] newArray(int size) {
            return new ProgressInfo[size];
        }
    };
}
