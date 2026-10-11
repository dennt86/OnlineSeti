#pragma once
#include <QWidget>
#include <QLineEdit>
#include <QComboBox>
#include <QSpinBox>
#include "profile_editor.h"
class EditOLCRTC : public QWidget, public ProfileEditor {
    Q_OBJECT
public:
    explicit EditOLCRTC(QWidget *parent = nullptr);
    void onStart(std::shared_ptr<Configs::Profile>) override;
    bool onEnd() override;
private:
    std::shared_ptr<Configs::Profile> ent;
    QLineEdit *room, *key, *token, *dns, *channel, *device;
    QComboBox *provider, *transport, *codec, *recovery;
    QMap<QString,QSpinBox*> numbers;
    QMap<QString,QWidget*> fields;
    void updateFields();
};
