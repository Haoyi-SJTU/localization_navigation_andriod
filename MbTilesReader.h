#ifndef MBTILESREADER_H
#define MBTILESREADER_H

#endif // MBTILESREADER_H


#include <QQuickImageProvider>
#include <QSqlDatabase>
#include <QSqlQuery>
#include <QSqlError>
#include <QDebug>

class MbTilesReader : public QQuickImageProvider
{
public:
    // 构造函数传入 .db 文件的路径
    MbTilesReader(const QString &dbPath)
        : QQuickImageProvider(QQuickImageProvider::Image)
    {
        m_db = QSqlDatabase::addDatabase("QSQLITE", "mapConnection");
        m_db.setDatabaseName(dbPath);
        if (!m_db.open()) {
            qWarning() << "Error opening map database:" << m_db.lastError();
        } else {
            qDebug() << "Map database opened successfully:" << dbPath;
        }
    }

    ~MbTilesReader() { m_db.close(); }

    QImage requestImage(const QString &id, QSize *size, const QSize &requestedSize) override
    {
        // QML 请求格式通常是: "z/x/y"
        QStringList parts = id.split('/');
        if (parts.count() < 3) return QImage();

        int z = parts[0].toInt();
        int x = parts[1].toInt();
        int y = parts[2].toInt();

        // MBTiles 标准中，Y 轴通常是翻转的 (TMS 格式)
        // 公式: try_y = (2^z - 1) - y
        int tms_y = (1 << z) - 1 - y;

        QSqlQuery query(m_db);
        // 大多数 .db 地图表名为 "tiles" 或 "images"
        query.prepare("SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?");
        query.addBindValue(z);
        query.addBindValue(x);
        query.addBindValue(tms_y);

        if (query.exec() && query.next()) {
            QByteArray data = query.value(0).toByteArray();
            QImage img;
            img.loadFromData(data);
            return img;
        }

        // 如果找不到瓦片，返回一个透明空图，防止报错
        QImage empty(256, 256, QImage::Format_ARGB32);
        empty.fill(Qt::transparent);
        return empty;
    }

private:
    QSqlDatabase m_db;
};
