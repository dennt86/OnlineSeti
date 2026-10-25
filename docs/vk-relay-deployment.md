# VK relay deployment contract for mobile and future desktop port

The Settings root opens VK relay between subscriptions and appearance. Account binding and the qWDTT default hash source are shared. qWDTT and WDTT Plus each have a server list, add button, and server editor. Desktop is not modified by this change.

## Reference sources

- qWDTT reference commit 684769a74531a8e0454eb347b041bc7c29d897a3; official release v1.4.4. Original deploy.sh matches the released APK byte-for-byte. APK SHA256 ff5b2d1f265b0c4253cdf54ebf1f8a5d64afd1424086aa8b8be754b5c14df6a7. Server ELF machine 62, x86_64 only.
- qWDTT and WDTT Plus are GPL-3.0. Commercial distribution requires compliance with GPL, including preserving notices and providing corresponding source for the distributed version.

## Install protocol

1. Validate host, credentials, ports and secrets locally. Snapshot server settings. Serialize deployment operations.
2. Connect through SSH. Before authentication, show first-use SHA256 server-key fingerprint and persist it only on explicit trust. Reject changed keys. Password and private key/passphrase are supported; non-root users can provide a sudo password.
3. Read uname -m. Obtain the pinned official APK, verify its full SHA256, extract only fixed asset names, and upload by SFTP mode 0600.
4. qWDTT: /tmp/deploy.sh, /tmp/wdtt-server, wdtt-main.password, wdtt-admin.token, wdtt-bot.token. Invoke installer with WDTT_DTLS_PORT/WG_PORT/RAW_PORT/DIRECT_PORT/ADMIN_PORT/SSH_PORT/ADMIN_ID/DNS_SERVERS. Defaults 56000/56001/56003/56002 (UDP direct)/56002 (TCP admin)/22. DNS defaults 1.1.1.1,1.0.0.1. Admin API token is generated locally. Optional Telegram ID/token remain optional.
5. Run the upstream installer under root or sudo, streaming stdout/stderr into a bounded console. Never log commands with credentials or secret-file content. Redact known secrets. Installation timeout 30 minutes. Cancellation disconnects SSH; it does not promise rollback of server changes already made.
6. Success requires SSH exit 0, WDTT_DEPLOY_OK and WDTT_ADMIN_PIN|sha256/.... Save the pin and admin token locally. Return profile JSON containing host, tunnel password, stable device ID, ports, and hash mode. Call hashes are not server secrets: enter them later or use the linked VK account. Do not invent ready-to-use hashes.
7. Uninstall requires explicit confirmation and runs the upstream uninstall command. Local record removal is a separate operation and never deletes a remote service.

## Storage and UI

The server editor uses paired host/login and DNS fields, a password/key selector, a separate compact secrets dialog, and an always-present collapsible console. Starting an operation scrolls the editor to the console once. The terminal follows new logs while at the bottom; manual scrolling up pauses following until the user returns to the bottom. WDTT_PROGRESS fractional stages are parsed. Completion requires the same marker, pin and exit checks and shows a toast. An importable qwdtt://config link is persisted per VPS and displayed in a compact collapsible card below the console. Legacy JSON configurations are converted for display. Successful remote uninstall clears the stored configuration; failure or cancellation keeps it.

The qWDTT legacy-server guard is preserved: reject /etc/wireguard or wg0 without wdtt0 in the official server asset. Runtime scripts/binaries remain the pinned official assets described above. Login mirrors upstream mobile-login/mobile-home/desktop-login fallback, including desktop UA, language headers, third-party cookies and Unknown method error detection. TLS verification remains enabled. Auto hashes are scoped to qWDTT; stored manual hash fields remain intact.

Server records and SSH secrets: Android Keystore AES-GCM in noBackupFilesDir; not included in .thrbackup or subscription/profile exports. Host and key modifications require renewed trust. Operation state survives activity recreation in process, with bounded logs, last result and configuration. UI shows known stages; installer stage is indeterminate, not an invented completion percentage.

## Validation limits

Resource validation and compiler/unit checks are performed. Real password/key login, distro installation, Docker install, bot management and uninstall still require a disposable authorized VPS. No live VPS deployment was performed while implementing this feature. Do not claim those flows have been proven by compiling alone.
