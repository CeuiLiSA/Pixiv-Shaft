package ceui.lisa.fragments;

import androidx.databinding.ViewDataBinding;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.core.LocalRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.models.IllustsBean;
import ceui.pixiv.db.DiscoveryEntity;
import ceui.pixiv.db.discovery.DiscoveryPool;
import ceui.pixiv.db.discovery.ProfileManager;
import ceui.pixiv.db.discovery.UserProfile;
import timber.log.Timber;

public class FragmentDiscovery extends LocalListFragment<FragmentBaseListBinding, IllustsBean> {

    private static final String TAG = "Discovery/Feed";

    public static FragmentDiscovery newInstance() {
        return new FragmentDiscovery();
    }

    @Override
    public void initView() {
        super.initView();
        Timber.d("%s initView >>> entered", TAG);
        String stats = DiscoveryPool.INSTANCE.getStats();
        Timber.d("%s initView pool: %s", TAG, stats);

        UserProfile profile = ProfileManager.INSTANCE.cached();
        if (profile != null) {
            Timber.d("%s initView profile: %d tags, %d authors, %d seeds, avgRate=%.4f",
                    TAG, profile.getTagScores().size(),
                    profile.getAuthorScores().size(),
                    profile.getSeedIllusts().size(),
                    profile.getAvgBookmarkRate());
            profile.topTags(5).forEach(pair ->
                    Timber.d("%s initView   top tag: '%s' lift=%.2f", TAG, pair.getFirst(), pair.getSecond()));
        } else {
            Timber.d("%s initView profile NOT READY", TAG);
        }
    }

    @Override
    public void initRecyclerView() {
        staggerRecyclerView();
    }

    @Override
    public BaseAdapter<?, ? extends ViewDataBinding> adapter() {
        return new IAdapter(allItems, mContext);
    }

    @Override
    public BaseRepo repository() {
        return new LocalRepo<List<IllustsBean>>() {
            @Override
            public List<IllustsBean> first() {
                Timber.d("%s first() >>>", TAG);
                List<DiscoveryEntity> entities = DiscoveryPool.INSTANCE.getDiscoveryFeedDiversified(PAGE_SIZE);
                List<IllustsBean> result = convertAndMark(entities);
                Timber.d("%s first() <<< %d entities -> %d illusts", TAG, entities.size(), result.size());
                return result;
            }

            @Override
            public List<IllustsBean> next() {
                Timber.d("%s next() >>>", TAG);
                // 不再使用 offset：markShown + recentlyReturnedIds 已确保不会重复
                List<DiscoveryEntity> entities = DiscoveryPool.INSTANCE.getDiscoveryFeedDiversified(PAGE_SIZE);
                List<IllustsBean> result = convertAndMark(entities);
                Timber.d("%s next() <<< %d entities -> %d illusts", TAG, entities.size(), result.size());
                return result;
            }

            @Override
            public boolean hasNext() {
                return true;
            }
        };
    }

    private List<IllustsBean> convertAndMark(List<DiscoveryEntity> entities) {
        List<IllustsBean> result = new ArrayList<>();
        for (DiscoveryEntity entity : entities) {
            try {
                IllustsBean illust = Shaft.sGson.fromJson(entity.getIllustJson(), IllustsBean.class);
                if (illust != null && illust.getId() > 0) {
                    result.add(illust);
                    DiscoveryPool.INSTANCE.markShown(entity.getIllustId());
                    Timber.d("%s   display id=%d score=%.2f source='%s' '%s' by '%s'",
                            TAG, entity.getIllustId(), entity.getScore(),
                            entity.getSource(), illust.getTitle(),
                            illust.getUser() != null ? illust.getUser().getName() : "?");
                }
            } catch (Exception e) {
                Timber.w(e, "%s   parse FAILED id=%d", TAG, entity.getIllustId());
            }
        }
        return result;
    }

    @Override
    public String getToolbarTitle() {
        return "发现";
    }

    @Override
    public boolean showToolbar() {
        return true;
    }
}
