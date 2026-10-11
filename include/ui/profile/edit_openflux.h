#pragma once
#include <QWidget>
#include <QLineEdit>
#include "profile_editor.h"
class EditOpenFlux : public QWidget, public ProfileEditor {
    Q_OBJECT
public:
    explicit EditOpenFlux(QWidget *parent = nullptr);
    void onStart(std::shared_ptr<Configs::Profile>) override;
    bool onEnd() override;
private:
    std::shared_ptr<Configs::Profile> ent;
    QLineEdit *secret, *context, *yandex, *mail, *cups;
};
