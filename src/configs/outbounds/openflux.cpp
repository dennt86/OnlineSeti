#include "include/configs/outbounds/openflux.h"
#include "include/global/Utils.hpp"
#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDir>
#include <QJsonDocument>
#include <QUrl>
#include <zlib.h>

namespace Configs {
namespace {
constexpr int payloadLimit = 16384;
QByteArray deflatePayload(const QByteArray &input, bool compress) {
    z_stream stream{};
    const int init = compress ? deflateInit2(&stream, Z_DEFAULT_COMPRESSION, Z_DEFLATED, -MAX_WBITS, 8, Z_DEFAULT_STRATEGY)
                              : inflateInit2(&stream, -MAX_WBITS);
    if (init != Z_OK) return {};
    QByteArray output(payloadLimit + 1, '\0');
    stream.next_in = reinterpret_cast<Bytef*>(const_cast<char*>(input.constData()));
    stream.avail_in = input.size();
    stream.next_out = reinterpret_cast<Bytef*>(output.data());
    stream.avail_out = output.size();
    const int status = compress ? deflate(&stream, Z_FINISH) : inflate(&stream, Z_FINISH);
    const bool valid = status == Z_STREAM_END && stream.total_out <= payloadLimit && stream.avail_in == 0;
    const auto size = stream.total_out;
    if (compress) deflateEnd(&stream); else inflateEnd(&stream);
    if (!valid) return {};
    output.resize(size);
    return output;
}
}
bool openflux::ParseFromLink(const QString &link) {
    if (!link.startsWith("openflux://v1/", Qt::CaseInsensitive) || link.size() > 32768) return false;
    const auto encoded = link.mid(14).section('#', 0, 0).toLatin1();
    const auto decoded = QByteArray::fromBase64Encoding(encoded, QByteArray::Base64UrlEncoding | QByteArray::AbortOnBase64DecodingErrors);
    if (!decoded) return false;
    auto object = QJsonDocument::fromJson(deflatePayload(decoded.decoded, false)).object();
    object["type"] = "openflux";
    if (link.contains('#')) object["name"] = QUrl::fromPercentEncoding(link.section('#', 1).toUtf8());
    return ParseFromJson(object);
}
bool openflux::ParseFromJson(const QJsonObject &o) {
    if (o["type"].toString() != "openflux" || !o["mode"].toString().isEmpty()) return false;
    name = o["name"].toString(o["tag"].toString());
    secret = o["secret"].toString(); context = o["context"].toString();
    codec = o["codec"].toString("batched"); negotiate = o["negotiate"].toBool(true);
    transports = o["transports"].toArray();
    server.clear();
    for (const auto &entry : transports) {
        const auto t = entry.toObject();
        if (t["type"] == "direct") {
            const QUrl address("tcp://" + t["dial"].toString());
            server = address.host(); server_port = address.port();
        }
        if (context.isEmpty() && !t["url"].toString().isEmpty()) context = t["url"].toString();
    }
    if (context.isEmpty()) context = "http://openflux";
    return Validate().isEmpty();
}
QString openflux::Validate() const {
    if (secret.size() < 16 || secret.contains('\n') || secret.contains('\r')) return QStringLiteral("OpenFlux: секрет должен содержать не менее 16 символов");
    if (transports.isEmpty() || transports.size() > 8 || !negotiate || (codec != "batched" && codec != "legacy")) return QStringLiteral("OpenFlux: неверные параметры каналов");
    const auto safe = [](const QString &s) { return !s.contains('\n') && !s.contains('\r') && !s.contains('#') && !s.contains(';'); };
    if (!safe(context)) return QStringLiteral("OpenFlux: неверный контекст подключения");
    QSet<QString> names;
    for (const auto &entry : transports) {
        const auto t = entry.toObject();
        const auto type = t["type"].toString(), transportName = t["name"].toString(type);
        if (!QStringList{"direct", "yandex", "mailru", "cupsonline"}.contains(type) || transportName != type || names.contains(type)) return QStringLiteral("OpenFlux: неподдерживаемый или повторяющийся канал");
        names.insert(type);
        if (type == "direct") {
            const QUrl address("tcp://" + t["dial"].toString());
            if (!safe(t["dial"].toString()) || address.host().isEmpty() || address.port() < 1 || address.port() > 65535 || !address.userInfo().isEmpty() || !address.path().isEmpty()) return QStringLiteral("OpenFlux: неверный адрес сервера");
        } else {
            const auto raw = t["url"].toString(); const QUrl url(raw);
            if (!safe(raw) || !url.isValid() || !QStringList{"http", "https"}.contains(url.scheme()) || url.host().isEmpty() || !url.userInfo().isEmpty()) return QStringLiteral("OpenFlux: неверная ссылка на документ");
        }
    }
    return {};
}
QJsonObject openflux::ExportToJson() {
    return {{"type", "openflux"}, {"name", name}, {"secret", secret}, {"context", context}, {"codec", codec}, {"negotiate", negotiate}, {"transports", transports}};
}
QJsonObject openflux::ExportIdentity() { auto o = ExportToJson(); o.remove("name"); return o; }
QString openflux::ExportToLink() {
    if (!Validate().isEmpty()) return {};
    auto o = ExportToJson(); o.remove("type");
    const auto data = deflatePayload(QJsonDocument(o).toJson(QJsonDocument::Compact), true);
    if (data.isEmpty()) return {};
    return "openflux://v1/" + QString::fromLatin1(data.toBase64(QByteArray::Base64UrlEncoding | QByteArray::OmitTrailingEquals));
}
QString openflux::Prepare() {
    if (const auto error = Validate(); !error.isEmpty()) return error;
#ifdef ONLINESETI_EDITION
    QString executable = "OnlineSetiOpenFlux";
#else
    QString executable = "openflux";
#endif
#ifdef Q_OS_WIN
    executable += ".exe";
#endif
    extraCorePath = QDir(QCoreApplication::applicationDirPath()).absoluteFilePath(executable);
    if (socksPort == 0) { const auto ports = MkManyPorts(1); if (ports.isEmpty()) return "OpenFlux: не удалось открыть локальный порт"; socksPort = ports.first(); }
    auto config = ExportToJson();
    config["backend"] = "openflux";
    config["socks"] = "127.0.0.1:" + QString::number(socksPort);
    config["peer"] = context;
    config["device_id"] = QString::fromLatin1(QCryptographicHash::hash(secret.toUtf8(), QCryptographicHash::Sha256).toHex());
    extraCoreConf = QString::fromUtf8(QJsonDocument(config).toJson(QJsonDocument::Compact));
    extraCoreArgs = "--qthrone-config %s";
    noLogs = true;
    return {};
}
}
