package dora.widget.banner

import android.content.Context
import android.view.View

/**
 * Banner Adapter。
 *
 * @param T Banner 数据类型。
 */
abstract class BannerAdapter<T> {

    /**
     * 获取 Banner 数量。
     */
    abstract fun getItemCount(): Int

    /**
     * 创建 Banner View。
     *
     * 每个 Banner 页面对应一个 View。
     */
    abstract fun onCreateView(context: Context): View

    /**
     * 绑定 Banner 数据。
     *
     * @param view Banner View。
     * @param position 数据位置。
     */
    abstract fun onBindView(
        view: View,
        position: Int
    )

    /**
     * 获取指定位置的数据。
     *
     * 默认返回 null。
     */
    open fun getItem(position: Int): T? {
        return null
    }

    /**
     * 数据刷新监听。
     */
    internal var onDataSetChangedListener: (() -> Unit)? = null

    /**
     * 通知 Banner 数据发生变化。
     */
    fun notifyDataSetChanged() {
        onDataSetChangedListener?.invoke()
    }
}