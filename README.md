dview-banner-view ![Release](https://jitpack.io/v/dora4/dview-banner-view.svg)
--------------------------------

#### Gradle依赖配置

```groovy
// 添加以下代码到项目根目录下的build.gradle
allprojects {
    repositories {
        maven { url "https://jitpack.io" }
    }
}
// 添加以下代码到app模块的build.gradle
dependencies {
    implementation 'com.github.dora4:dview-banner-view:1.0'
}
```

#### 控件使用

```xml
<dora.widget.DoraBannerView
    android:id="@+id/bannerView"
    android:layout_width="match_parent"
    android:layout_height="180dp"
    app:dview_bv_autoPlay="true"
    app:dview_bv_interval="3000"
    app:dview_bv_duration="350"
    app:dview_bv_loop="true"
    app:dview_bv_indicatorVisible="true"
    app:dview_bv_indicatorRadius="4dp"
    app:dview_bv_indicatorSpace="8dp"
    app:dview_bv_indicatorBottomMargin="12dp"
    app:dview_bv_indicatorNormalColor="#66FFFFFF"
    app:dview_bv_indicatorSelectedColor="#FFFFFFFF" />
```
```kotlin
binding.bannerView.apply {
    setItems(
        R.drawable.banner_1,
        R.drawable.banner_2,
        R.drawable.banner_3
    )
    setOnBannerClickListener { _, position ->
        when (position) {
            0 -> {
                // 广告 1
            }
            1 -> {
                // 广告 2
            }
            2 -> {
                // 广告 3
            }
        }
    }
    setOnPageChangedListener { _, position ->
        // 页面切换
    }
}
```