package io.github.proify.lyricon.miuiprovider.xposed

object Constants {
    const val PROVIDER_PACKAGE_NAME = "io.github.proify.lyricon.miuiprovider"
    const val MUSIC_PACKAGE_NAME = "com.miui.player"

    // 小米音乐风格：橙底 + 白色八分音符（词幕商店里的源图标）
    const val ICON: String =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\">" +
            "<rect width=\"48\" height=\"48\" rx=\"12\" fill=\"#FF5A1F\"/>" +
            "<path d=\"M26 8v18.6A7.6 7.6 0 1 0 30 33V16h6V8h-10z\" fill=\"#FFFFFF\"/>" +
            "</svg>"
}
