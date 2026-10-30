import json
import shutil
import stat
import subprocess
import sys
from pathlib import Path


MODULE = "github.com/sagernet/sing-box"


def replace_once(path: Path, before: str, after: str):
    content = path.read_text()
    count = content.count(before)
    if count != 1:
        raise ValueError(f"Unexpected upstream source in {path.name}: expected one patch point, found {count}")
    path.write_text(content.replace(before, after), newline="\n")


def patch(core: Path):
    info = json.loads(subprocess.check_output(["go", "mod", "download", "-json", MODULE], cwd=core))
    version = info.get("Version", "")
    if not version.startswith("v1.11.16-0.20261003011458-"):
        raise ValueError(f"Unexpected sing-box version {version!r}; review the cellular failover patch")
    if info.get("Error") or not info.get("Dir"):
        raise ValueError(f"Could not resolve sing-box source: {info.get('Error', 'missing source directory')}")

    target = core / ".qthrone" / "sing-box-cellular"
    if target.exists():
        for old_file in target.rglob("*"):
            if old_file.is_file():
                old_file.chmod(stat.S_IWRITE | stat.S_IREAD)
        shutil.rmtree(target)
    shutil.copytree(info["Dir"], target, ignore=shutil.ignore_patterns(".git", "vendor"))

    options = target / "option" / "group.go"
    replace_once(options,
        '\tPinned string `json:"pinned,omitempty"`',
        '\tPinned string `json:"pinned,omitempty"`\n\t// PinnedFailoverFailures delays cellular reserve traffic until repeated real probe failures.\n\tPinnedFailoverFailures int `json:"pinned_failover_failures,omitempty"`')

    health = target / "protocol" / "group" / "autoselector_health.go"
    replace_once(health,
        '\tprobes    int\n',
        '\tprobes    int\n\tconsecutiveProbeFailures int\n')
    replace_once(health,
        '\th.probes++\n\th.lastProbe = at\n\tif value != rttFailed && value != rttUntested {\n\t\th.lastOK = at\n\t}\n',
        '\th.probes++\n\th.lastProbe = at\n\tif value == rttFailed {\n\t\th.consecutiveProbeFailures++\n\t} else if value != rttUntested {\n\t\th.consecutiveProbeFailures = 0\n\t\th.lastOK = at\n\t}\n')

    selector = target / "protocol" / "group" / "autoselector.go"
    replace_once(selector,
        '\tpinnedTag        string\n',
        '\tpinnedTag        string\n\tpinnedFailoverFailures int\n')
    replace_once(selector,
        '\tif common.Contains(s.tags, options.Pinned) {\n\t\ts.pinnedTag = options.Pinned\n\t}',
        '\ts.pinnedFailoverFailures = options.PinnedFailoverFailures\n\tif common.Contains(s.tags, options.Pinned) {\n\t\ts.pinnedTag = options.Pinned\n\t}')
    replace_once(selector,
        '\tif !loaded || node.tier != tierHealthy {\n\t\treturn nil\n\t}\n\tdetour, loaded := s.outbounds[s.pinnedTag]',
        '\tif !loaded {\n\t\treturn nil\n\t}\n\tif node.tier != tierHealthy {\n\t\thealth := s.members[s.pinnedTag]\n\t\tif s.pinnedFailoverFailures <= 0 || health == nil || health.consecutiveProbeFailures >= s.pinnedFailoverFailures {\n\t\t\treturn nil\n\t\t}\n\t}\n\tdetour, loaded := s.outbounds[s.pinnedTag]')
    replace_once(selector,
        '\tif len(candidates) == 0 {\n\t\t// Nothing qualified for this network.',
        '\tif len(candidates) == 0 {\n\t\t// After the configured failures, wait for an actually qualified reserve.\n\t\tif s.pinnedFailoverFailures > 0 && s.pinnedTag != "" {\n\t\t\tif health := s.members[s.pinnedTag]; health != nil && health.consecutiveProbeFailures >= s.pinnedFailoverFailures {\n\t\t\t\treturn\n\t\t\t}\n\t\t}\n\t\t// Nothing qualified for this network.')
    replace_once(selector,
        '\t// Balancing and a pinned member are contradictory instructions; the explicit\n\t// one wins. Spreading traffic resumes by itself once the pin is released or\n\t// the member stops being healthy.\n\tbalancing := s.balance && s.pinnedTag == ""',
        '\t// Before the configured consecutive-probe threshold, keep traffic on the\n\t// cellular pin even when it is temporarily unhealthy. Never leak traffic to\n\t// a reserve during a short WDTT interruption.\n\tif s.pinnedFailoverFailures > 0 && s.pinnedTag != "" {\n\t\tif health := s.members[s.pinnedTag]; health != nil && health.consecutiveProbeFailures < s.pinnedFailoverFailures {\n\t\t\tif pinned := s.outbounds[s.pinnedTag]; pinned != nil && common.Contains(pinned.Network(), network) && !tried[pinned.Tag()] {\n\t\t\t\treturn pinned\n\t\t\t}\n\t\t}\n\t}\n\t// Balancing and a pinned member are contradictory instructions; the explicit\n\t// one wins. Spreading traffic resumes by itself once the pin is released or\n\t// the member stops being healthy.\n\tbalancing := s.balance && s.pinnedTag == ""')
    replace_once(selector,
        '\t// Balancing and a pinned member are contradictory instructions; the explicit\n\t// one wins. Spreading traffic resumes by itself once the pin is released or\n\t// the member stops being healthy.\n\tbalancing := s.balance && s.pinnedTag == ""',
        '\t// Until five (or the configured number of) consecutive real probe failures,\n\t// keep traffic on the pin. Once the threshold is met, use only a qualified reserve.\n\tif s.pinnedFailoverFailures > 0 && s.pinnedTag != "" {\n\t\thealth := s.members[s.pinnedTag]\n\t\tif health != nil && health.consecutiveProbeFailures < s.pinnedFailoverFailures {\n\t\t\tif pinned := s.outbounds[s.pinnedTag]; pinned != nil && common.Contains(pinned.Network(), network) && !tried[pinned.Tag()] {\n\t\t\t\treturn pinned\n\t\t\t}\n\t\t\treturn nil\n\t\t}\n\t\tfor _, tag := range s.qualified {\n\t\t\tif tag == s.pinnedTag {\n\t\t\t\tcontinue\n\t\t\t}\n\t\t\tif detour := s.usableLocked(tag, network, tried, now, false); detour != nil {\n\t\t\t\treturn detour\n\t\t\t}\n\t\t}\n\t\treturn nil\n\t}\n\t// Balancing and a pinned member are contradictory instructions; the explicit\n\t// one wins. Spreading traffic resumes by itself once the pin is released or\n\t// the member stops being healthy.\n\tbalancing := s.balance && s.pinnedTag == ""')

    subprocess.run(["go", "mod", "edit", f"-replace={MODULE}=./.qthrone/sing-box-cellular"], cwd=core, check=True)


if __name__ == "__main__":
    patch(Path(sys.argv[1]).resolve())
