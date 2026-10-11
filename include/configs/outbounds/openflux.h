#pragma once
#include "include/configs/outbounds/extracore.h"
#include <QJsonArray>

namespace Configs {
class openflux : public extracore {
public:
    openflux() { server_port = 18445; socksPort = 0; }
    QString secret, context;
    QString codec = "batched";
    bool negotiate = true;
    QJsonArray transports;
    bool ParseFromLink(const QString &) override;
    bool ParseFromJson(const QJsonObject &) override;
    QJsonObject ExportToJson() override;
    QJsonObject ExportIdentity() override;
    QString ExportToLink() override;
    QString DisplayType() override { return "OpenFlux"; }
    SecurityInfo GetSecurity() override { return {"OpenFlux AEAD", "Documents / Direct", SecurityLevel::Secure}; }
    QString Validate() const;
    QString Prepare();
    bool SupportsCredentialStrip() const override { return true; }
    void StripCredentials() override { secret.clear(); transports = {}; }
};
}
