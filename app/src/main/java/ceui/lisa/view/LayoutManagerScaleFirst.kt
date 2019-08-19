//package ceui.lisa.view
//
//import android.content.Context
//import android.view.View
//
//class LayoutManagerScaleFirst: androidx.recyclerview.widget.LinearLayoutManager {
//    constructor(context: Context?) : super(context)
//    constructor(context: Context?, orientation: Int, reverseLayout: Boolean) : super(context, orientation, reverseLayout)
//
//    var oldChild0: View?=null
//    val scaleMinFactor=0.7f //from 0 to 1
//    override fun scrollVerticallyBy(dy: Int, recycler: androidx.recyclerview.widget.RecyclerView.Recycler, state: androidx.recyclerview.widget.RecyclerView.State?): Int {
//        val result=super.scrollVerticallyBy(dy, recycler, state)
//        if(childCount>1){
//            val child1=getChildAt(1)
//            val child0=getChildAt(0)
//            if(oldChild0!=null&&oldChild0!=child0){
//                resetChild(oldChild0!!)
//            }
//            oldChild0=child0
//            val scale=scaleMinFactor+(1-scaleMinFactor)*(child1?.top!!)/getDecoratedMeasuredHeight(child1)
//
//            viewAnimate(child0!!,scale,getDecoratedMeasuredHeight(child1)-child1.top.toFloat())
//            if(scale<scaleMinFactor){
//                resetChild(child0)
//                removeAndRecycleView(child0,recycler)
//            }
//        }
//        return  result
//    }
//    private fun resetChild(child:View){
//        viewAnimate(child,1f,0f)
//    }
//
//    private fun viewAnimate(child: View,factor:Float,transY:Float){
//        child.apply {
//            pivotX=this.width/2f
//            pivotY=this.height/1f
//            scaleX=factor
//            scaleY=factor
//            alpha=factor
//            translationY=transY
//        }
//    }
//}