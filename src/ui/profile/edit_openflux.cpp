#include "include/ui/profile/edit_openflux.h"
#include "include/configs/outbounds/openflux.h"
#include <QFormLayout>
EditOpenFlux::EditOpenFlux(QWidget *parent) : QWidget(parent) {
    auto *form = new QFormLayout(this);
    secret = new QLineEdit(this); secret->setEchoMode(QLineEdit::Normal);
    context = new QLineEdit(this); context->setPlaceholderText(QStringLiteral("Автоматически из ссылки на документ"));
    yandex = new QLineEdit(this); mail = new QLineEdit(this); cups = new QLineEdit(this);
    form->addRow(QStringLiteral("Секрет"), secret);
    form->addRow(QStringLiteral("Документ Яндекса"), yandex);
    form->addRow(QStringLiteral("Документ Mail.ru"), mail);
    form->addRow(QStringLiteral("Документ Cups.online"), cups);
    form->addRow(QStringLiteral("Контекст подключения"), context);
}
void EditOpenFlux::onStart(std::shared_ptr<Configs::Profile> profile) {
    ent = std::move(profile);
    auto *o = dynamic_cast<Configs::openflux*>(ent->outbound.get());
    if (!o) return;
    secret->setText(o->secret); context->setText(o->context);
    for (const auto &entry : o->transports) {
        const auto t = entry.toObject(); const auto type = t["type"].toString();
        if (type == "yandex") yandex->setText(t["url"].toString());
        if (type == "mailru") mail->setText(t["url"].toString());
        if (type == "cupsonline") cups->setText(t["url"].toString());
    }
}
bool EditOpenFlux::onEnd() {
    auto *o = dynamic_cast<Configs::openflux*>(ent->outbound.get());
    if (!o) return false;
    Configs::openflux updated;
    updated.name = o->name; updated.secret = secret->text();
    updated.server = get_edit_text_serverAddress().trimmed(); updated.server_port = get_edit_text_serverPort().toInt();
    if (!updated.server.isEmpty()) updated.transports.append(QJsonObject{{"type", "direct"}, {"priority", 100}, {"dial", (updated.server.contains(':') ? "[" + updated.server + "]" : updated.server) + ":" + QString::number(updated.server_port)}});
    const auto add = [&updated](const QString &type, const QString &url, int priority) {
        if (!url.trimmed().isEmpty()) updated.transports.append(QJsonObject{{"type", type}, {"url", url.trimmed()}, {"priority", priority}});
    };
    add("yandex", yandex->text(), 50); add("mailru", mail->text(), 40); add("cupsonline", cups->text(), 30);
    updated.context = context->text().trimmed();
    if (updated.context.isEmpty()) updated.context = !yandex->text().trimmed().isEmpty() ? yandex->text().trimmed() : !mail->text().trimmed().isEmpty() ? mail->text().trimmed() : !cups->text().trimmed().isEmpty() ? cups->text().trimmed() : "http://openflux";
    if (const auto error = updated.Validate(); !error.isEmpty()) { MessageBoxWarning("OpenFlux", error); return false; }
    return o->ParseFromJson(updated.ExportToJson());
}
