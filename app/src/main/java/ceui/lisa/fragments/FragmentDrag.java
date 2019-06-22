package ceui.lisa.fragments;

import android.os.Handler;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.facebook.rebound.SimpleSpringListener;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringConfig;
import com.facebook.rebound.SpringSystem;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.GlideUtil;

public class FragmentDrag extends BaseFragment{

    public static List<IllustsBean> allItems = new ArrayList<>();
    private List<ImageView> mImageViewList = new ArrayList<>();
    private Handler mHandler;
    private SpringSystem mSystem = SpringSystem.create();

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_drag;
    }

    @Override
    View initView(View v) {
        int[] position1 = new int[2];
        ImageView imageView3 = v.findViewById(R.id.image_3);
        ImageView imageView4 = v.findViewById(R.id.image_4);
        ImageView imageView5 = v.findViewById(R.id.image_5);
        ImageView imageView6 = v.findViewById(R.id.image_6);
        mImageViewList.add(imageView3);
        mImageViewList.add(imageView4);
        mImageViewList.add(imageView5);
        mImageViewList.add(imageView6);


        ImageView imageView1 = v.findViewById(R.id.image_1);
        imageView1.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                imageView1.getLocationOnScreen(position1);
                Common.showLog("imageView1 width " + position1[0]);
                Common.showLog("imageView1 height " + position1[1]);
                imageView1.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            }
        });

        int[] position2 = new int[2];
        ImageView imageView2 = v.findViewById(R.id.image_2);
        imageView2.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                imageView2.getLocationOnScreen(position2);
                Common.showLog("imageView2 width " + position2[0]);
                Common.showLog("imageView2 height " + position2[1]);
                imageView2.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            }
        });


        Glide.with(mContext).load(GlideUtil.getMediumImg(allItems.get(0))).into(imageView1);
        Glide.with(mContext).load(GlideUtil.getMediumImg(allItems.get(1))).into(imageView2);
        Glide.with(mContext).load(GlideUtil.getMediumImg(allItems.get(2))).into(imageView3);
        Glide.with(mContext).load(GlideUtil.getMediumImg(allItems.get(3))).into(imageView4);
        Glide.with(mContext).load(GlideUtil.getMediumImg(allItems.get(4))).into(imageView5);
        Glide.with(mContext).load(GlideUtil.getMediumImg(allItems.get(5))).into(imageView6);

        mHandler = new Handler();
        imageView1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                start();
            }
        });

        return v;
    }

    @Override
    void initData() {

    }



    class AnimeRunnable implements Runnable{

        private int index;
        private int startX;
        private int startY;
        private int endX;

        public Spring getSpring() {
            return mSpring;
        }

        public void setSpring(Spring spring) {
            mSpring = spring;
        }

        private Spring mSpring, mSpringYY;

        public View getView() {
            return mView;
        }

        public void setView(View view) {
            mView = view;
        }

        private View mView;



        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public int getStartX() {
            return startX;
        }

        public void setStartX(int startX) {
            this.startX = startX;
        }

        public int getStartY() {
            return startY;
        }

        public void setStartY(int startY) {
            this.startY = startY;
        }

        public int getEndX() {
            return endX;
        }

        public void setEndX(int endX) {
            this.endX = endX;
        }

        public int getEndY() {
            return endY;
        }

        public void setEndY(int endY) {
            this.endY = endY;
        }

        private int endY;

        @Override
        public void run() {
            mSpring = mSystem.createSpring();
            mSpring.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(40, 15));

            mSpring.addListener(new SimpleSpringListener(){
                @Override
                public void onSpringUpdate(Spring spring) {
                    mView.setTranslationX((float) spring.getCurrentValue());
                }
            });
            mSpring.setCurrentValue(startX);
            mSpring.setEndValue(endX);



            mSpringYY = mSystem.createSpring();
            mSpringYY.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(40, 12));
            mSpringYY.addListener(new SimpleSpringListener(){
                @Override
                public void onSpringUpdate(Spring spring) {
                    mView.setTranslationY((float) spring.getCurrentValue());
                }
            });
            mSpringYY.setCurrentValue(startY);
            mSpringYY.setEndValue(endY);
        }
    }

    private void start(){
//        SpringChain springChain = SpringChain.create(40,100,50,100);
//
//        int childCount = mImageViewList.size();
//
//        for (int i = 0; i < mImageViewList.size(); i++) {
//
//            final int position = i;
//            springChain.addSpring(new SimpleSpringListener() {
//                @Override
//                public void onSpringUpdate(Spring spring) {
//                    mImageViewList.get(position).setTranslationX((float) -spring.getCurrentValue());
//                }
//            });
//        }
//
//
//        int left = 0;
//        int top = 400;
//        List<Spring> springs = springChain.getAllSprings();
//        for (int i = 0; i < springs.size(); i++) {
////            int[] temp = new int[2];
////            mImageViewList.get(i).getLocationOnScreen(temp);
////
////            left = left + 48;
////            springs.get(i).setEndValue(left - temp[0]);
////            springs.get(i).setCurrentValue(0);
//            springs.get(i).setCurrentValue(0);
//            if(i == 0){
//                springs.get(i).setEndValue(200);
//            }else if(i == 1){
//                springs.get(i).setEndValue(400);
//            }else if(i == 2){
//                springs.get(i).setEndValue(600);
//            }else if(i == 3){
//                springs.get(i).setEndValue(800);
//            }
//            Common.showLog("springs.get(i).setEndValue " + left);
//        }

        //springChain.setControlSpringIndex(0).getControlSpring().setEndValue(700);

//        SpringSystem springSystem = SpringSystem.create();
//        for (int i = 0; i < 4; i++) {
//            if(i == 0){
//
//                Spring spring = springSystem.createSpring();
//                Spring springY = springSystem.createSpring();
//                springY.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(40, 10));
//                springY.addListener(new SimpleSpringListener(){
//                    @Override
//                    public void onSpringUpdate(Spring spring) {
//                        //super.onSpringUpdate(spring);
//                        mImageViewList.get(0).setTranslationY((float) -spring.getCurrentValue());
//                    }
//                });
//                springY.setCurrentValue(0);
//                springY.setEndValue(1400);
//                spring.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(40, 10));
//                spring.addListener(new SimpleSpringListener(){
//                    @Override
//                    public void onSpringUpdate(Spring spring) {
//                        //super.onSpringUpdate(spring);
//                        mImageViewList.get(0).setTranslationX((float) -spring.getCurrentValue());
//                    }
//                });
//                spring.setCurrentValue(0);
//                spring.setEndValue(200);
//            }else if(i == 1){
//                Spring spring = springSystem.createSpring();
//                Spring springY = springSystem.createSpring();
//                springY.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(40, 10));
//                springY.addListener(new SimpleSpringListener(){
//                    @Override
//                    public void onSpringUpdate(Spring spring) {
//                        //super.onSpringUpdate(spring);
//                        mImageViewList.get(1).setTranslationY((float) -spring.getCurrentValue());
//                    }
//                });
//                springY.setCurrentValue(0);
//                springY.setEndValue(1400);
//                spring.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(40, 10));
//                spring.addListener(new SimpleSpringListener(){
//                    @Override
//                    public void onSpringUpdate(Spring spring) {
//                        //super.onSpringUpdate(spring);
//                        mImageViewList.get(1).setTranslationX((float) -spring.getCurrentValue());
//                    }
//                });
//                spring.setCurrentValue(0);
//                spring.setEndValue(400);
//            }else if(i == 2){
//                Spring spring = springSystem.createSpring();
//                Spring springY = springSystem.createSpring();
//                springY.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(40, 10));
//                springY.addListener(new SimpleSpringListener(){
//                    @Override
//                    public void onSpringUpdate(Spring spring) {
//                        //super.onSpringUpdate(spring);
//                        mImageViewList.get(2).setTranslationY((float) -spring.getCurrentValue());
//                    }
//                });
//                springY.setCurrentValue(0);
//                springY.setEndValue(1400);
//                spring.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(40, 10));
//                spring.addListener(new SimpleSpringListener(){
//                    @Override
//                    public void onSpringUpdate(Spring spring) {
//                        //super.onSpringUpdate(spring);
//                        mImageViewList.get(2).setTranslationX((float) -spring.getCurrentValue());
//                    }
//                });
//                spring.setCurrentValue(0);
//                spring.setEndValue(600);
//            }else if(i == 3){
//                Spring spring = springSystem.createSpring();
//                Spring springY = springSystem.createSpring();
//                springY.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(40, 10));
//                springY.addListener(new SimpleSpringListener(){
//                    @Override
//                    public void onSpringUpdate(Spring spring) {
//                        //super.onSpringUpdate(spring);
//                        mImageViewList.get(3).setTranslationY((float) -spring.getCurrentValue());
//                    }
//                });
//                springY.setCurrentValue(0);
//                springY.setEndValue(1400);
//                spring.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(40, 10));
//                spring.addListener(new SimpleSpringListener(){
//                    @Override
//                    public void onSpringUpdate(Spring spring) {
//                        //super.onSpringUpdate(spring);
//                        mImageViewList.get(3).setTranslationX((float) -spring.getCurrentValue());
//                    }
//                });
//                spring.setCurrentValue(0);
//                spring.setEndValue(800);
//            }
//        }
//        SpringChain  chain =SpringChain.create(40,10,50,7);
//        for(int i=0;i<mImageViewList.size();i++){
//            final ImageView view = mImageViewList.get(i);
//            final int  index = i;
//            chain.addSpring(new SimpleSpringListener(){
//                @Override
//                public void onSpringUpdate(Spring spring) {
//                    view.setTranslationY((float) -spring.getCurrentValue());
//                }
//            });
//        }
//        List<Spring> springs = chain.getAllSprings();
//        for (int i = 0; i < springs.size(); i++) {
//            springs.get(i).setCurrentValue(0);
//        }
//
//        chain.setControlSpringIndex(3).getControlSpring().setEndValue(1500);
//
//
//        SpringChain  XXXXX =SpringChain.create(10,10,50,7);
//        for(int i=0;i<mImageViewList.size();i++){
//            final ImageView view = mImageViewList.get(i);
//            final int  index = i;
//            XXXXX.addSpring(new SimpleSpringListener(){
//                @Override
//                public void onSpringUpdate(Spring spring) {
//                    view.setTranslationX((float) -spring.getCurrentValue());
//                    if(index == 0) {
//
//                        Common.showLog("hahahhahah " + -spring.getCurrentValue());
//                    }
//                }
//            });
//        }
//
//
//        int[] abc = new int[]{200, 400, 600, 800};
//        List<Spring> springsXXXX = XXXXX.getAllSprings();
//        for (int i = 0; i < springsXXXX.size(); i++) {
//            springsXXXX.get(i).setCurrentValue(0);
//            springsXXXX.get(i).setEndValue(abc[i]);
//        }
//
//        XXXXX.setControlSpringIndex(3).getControlSpring().setEndValue(800);


        for (int i = 0; i < 4; i++) {
            if(i == 0){
                AnimeRunnable animeRunnable = new AnimeRunnable();
                animeRunnable.setStartX(0);
                animeRunnable.setEndX(-800);
                animeRunnable.setStartY(0);
                animeRunnable.setEndY(-1300);
                animeRunnable.setView(mImageViewList.get(i));
                mHandler.postDelayed(animeRunnable, 200L);
            }else if(i == 1){
                AnimeRunnable animeRunnable = new AnimeRunnable();
                animeRunnable.setStartX(0);
                animeRunnable.setEndX(-600);
                animeRunnable.setStartY(0);
                animeRunnable.setEndY(-1300);
                animeRunnable.setView(mImageViewList.get(i));
                mHandler.postDelayed(animeRunnable, 400L);
            }else if(i == 2){
                AnimeRunnable animeRunnable = new AnimeRunnable();
                animeRunnable.setStartX(0);
                animeRunnable.setEndX(-400);
                animeRunnable.setStartY(0);
                animeRunnable.setEndY(-1300);
                animeRunnable.setView(mImageViewList.get(i));
                mHandler.postDelayed(animeRunnable, 600L);
            }else if(i == 3){
                AnimeRunnable animeRunnable = new AnimeRunnable();
                animeRunnable.setStartX(0);
                animeRunnable.setEndX(-200);
                animeRunnable.setStartY(0);
                animeRunnable.setEndY(-1300);
                animeRunnable.setView(mImageViewList.get(i));
                mHandler.postDelayed(animeRunnable, 800L);
            }
        }


    }
}
