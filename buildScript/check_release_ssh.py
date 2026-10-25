"""Check actual DEX class definitions, rather than class-name strings, in release APKs."""
from pathlib import Path
import struct
import sys
import zipfile

REQUIRED = {"Lcom/jcraft/jsch/jce/Random;", "Lcom/jcraft/jsch/JSch;", "Lcom/jcraft/jsch/Session;"}
REQUIRED.update("Lcom/jcraft/jsch/" + name + ";" for name in (
    "jce/AES128CTR", "jce/SHA256", "jce/HMACSHA256", "jce/SignatureRSASHA256",
    "jbcrypt/JBCrypt", "DHG14256", "UserAuthNone", "UserAuthPassword",
    "UserAuthKeyboardInteractive", "UserAuthPublicKey"))
DESKTOP_ONLY = {"Lcom/jcraft/jsch/PageantConnector;", "Lcom/jcraft/jsch/JUnixSocketFactory;",
                "Lcom/jcraft/jsch/Log4j2Logger;", "Lcom/jcraft/jsch/Slf4jLogger;",
                "Lcom/jcraft/jsch/jgss/GSSContextKrb5;"}

def classes(data):
    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]
    string_count, strings = u32(56), u32(60)
    type_count, types = u32(64), u32(68)
    class_count, definitions = u32(96), u32(100)
    def descriptor(index):
        assert index < type_count
        string_index = u32(types + index * 4)
        assert string_index < string_count
        offset = u32(strings + string_index * 4)
        while data[offset] & 128:
            offset += 1
        offset += 1
        return data[offset:data.index(b"\0", offset)].decode("utf-8")
    return {descriptor(u32(definitions + index * 32)) for index in range(class_count)}

def verify(directory):
    apks = sorted(Path(directory).rglob("*.apk"))
    assert apks, "No release APKs found"
    for apk in apks:
        found = set()
        with zipfile.ZipFile(apk) as archive:
            for name in archive.namelist():
                if name.startswith("classes") and name.endswith(".dex"):
                    found.update(classes(archive.read(name)))
        missing = REQUIRED - found
        assert not missing, f"{apk.name}: missing SSH implementations: {sorted(missing)}"
        assert not (DESKTOP_ONLY & found), f"{apk.name}: unused desktop SSH adapters retained: {sorted(DESKTOP_ONLY & found)}"
        print(f"{apk.name}: required SSH implementations retained")

if __name__ == "__main__":
    verify(sys.argv[1])
