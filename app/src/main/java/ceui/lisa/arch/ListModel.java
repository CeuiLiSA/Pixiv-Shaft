package ceui.lisa.arch;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.core.RemoteRepo;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.interfaces.ListShow;

/**
 * 李龙：
 * 1、旧件归还签收流程优化 ---宋金龙
 * 2、旧件直接归还功能开发 ---宋金龙
 * 3、返厂报废功能开发----宋金龙
 * 4、厂家通用版本bug功能修改优化（设备管理、鉴定单修改消失，工单复制恢复等功能）-----纪海东
 * 5、安时达抓单功能迁移到本地版--金志宇
 * 6、商品上架功能支持价格根据规则定义-----小邓
 * 7、华帝质保码机号校验功能增加----海东
 * 8、服务商给师傅结算功能---宋金龙
 * 9、订货管理功能开发，支持两种订货模式，配置文件内配置1、总部订货 2、直属上级订货---小邓、杨鑫、武猛
 * 10、网点App收款bug解决----海东
 * 11、厂家通用版投诉页面问题处理----海东
 * 12、停用的账号还可以登录电脑，要限制下----纪海东
 * 13、爱玛带参数二维码功能开发----小邓
 * 14、转售后的功能不稳定----小邓
 * 15、机号校验功能（华帝）---海东
 * <p>
 * 安时达：
 * 1.发票号码自动识别 ---李瑞
 * 2.云米物料关联SN/69码申请和耗用---李瑞
 * <p>
 * 1 增加增值产品的调帐功能 --董军瑶
 * 2 云米换货、退货对接---董军瑶
 * 3 工单系统的组织管理变更---董军瑶
 * 4 APP耗用物料逻辑修改----董军瑶
 * <p>
 * 优先级不高：
 * 19、物料申请进度App端呈现（杨鑫、宋金龙）
 * 19、网点版本保养提醒功能优化（海东）
 * 20、厂家版工单回访项目自定义功能开发，在工单审核页面回访信息处显示回访时间（海东）
 */

public abstract class ListModel<Item, Resp extends ListShow<Item>> extends ViewModel {

    protected MutableLiveData<List<Item>> content = null;
    protected int lastSize;
    protected String nextUrl;
    protected int state; // 0闲置，1刷新，2加载更多
    protected RemoteRepo<Resp> repo;

    public ListModel() {
        repo = repository();
    }

    public abstract RemoteRepo<Resp> repository();

    public MutableLiveData<List<Item>> getContent() {
        if (content == null) {
            content = new MutableLiveData<>();
            content.setValue(new ArrayList<>());
            getFirstData();
        }
        return content;
    }

    public void getFirstData() {
        List<Item> now = content.getValue();
        if (now == null) {
            return;
        }
        if (now.size() != 0) {
            now.clear();
        }
        lastSize = 0;

        repo.getFirstData(new NullCtrl<Resp>() {
            @Override
            public void success(Resp listNovel) {
                state = 1;
                nextUrl = listNovel.getNextUrl();
                now.addAll(listNovel.getList());
                content.setValue(now);
            }
        });
    }

    public void loadMore() {
        List<Item> now = content.getValue();
        if (now == null) {
            return;
        }
        lastSize = now.size();

        repo.getNextData(new NullCtrl<Resp>() {
            @Override
            public void success(Resp listNovel) {
                state = 2;
                now.addAll(listNovel.getList());
                content.setValue(now);
            }
        });
    }

    public int getLastSize() {
        return lastSize;
    }

    public int getState() {
        return state;
    }
}
