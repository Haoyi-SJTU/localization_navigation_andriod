#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include "MbTilesReader.h"
#include <QStandardPaths>

int main(int argc, char *argv[])
{
    QGuiApplication app(argc, argv);

    QQmlApplicationEngine engine;

    // --- 离线地图配置开始 ---
    // 注意：Android 10+ 可能需要动态申请读取存储权限，或者手动把文件 push 到 App 私有目录
    QString mapPath = "/sdcard/offline_map/zhejiang.osm.db";

    // 注册图片提供器，在 QML 中可以通过 "image://offlinemap/..." 访问
    engine.addImageProvider("offlinemap", new MbTilesReader(mapPath));
    // --- 离线地图配置结束 ---

    // ... 原有的加载代码 ...
    const QUrl url(u"qrc:/newwww/Main.qml"_qs);
    // ...
    return app.exec();
}
