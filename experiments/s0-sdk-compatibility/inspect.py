"""Fetch exact official S0 inputs into ignored target/ and record their digests.

No product files or Maven settings are changed. No files are deleted.
Run from any directory with Python 3; only standard-library modules are used.
"""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import urllib.request
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "target" / "s0-inspection"
CENTRAL = "https://repo.maven.apache.org/maven2/"
SPEC = "https://modelcontextprotocol.io/specification/2026-07-28/"
AI_VERSIONS = ("2.0.1", "2.0.2", "2.1.0-RC1")
SDK_VERSION = "2.2.0-M1"
SPEC_PAGES = (
    "basic/index", "basic/versioning", "basic/transports/streamable-http",
    "basic/patterns", "basic/patterns/mrtr", "basic/patterns/subscriptions",
    "basic/patterns/cancellation", "basic/patterns/progress",
    "basic/authorization/index", "basic/authorization/authorization-server-discovery",
    "basic/authorization/client-registration", "basic/authorization/security-considerations",
    "server/discover", "server/tools", "server/utilities/caching", "server/utilities/pagination",
    "server/utilities/logging", "schema",
)


def inputs():
    urls = {
        "sdk-metadata.xml": CENTRAL + "io/modelcontextprotocol/sdk/mcp/maven-metadata.xml",
        "ai-metadata.xml": CENTRAL + "org/springframework/ai/spring-ai-bom/maven-metadata.xml",
        "sdk-release.json": "https://api.github.com/repos/modelcontextprotocol/java-sdk/releases?per_page=5",
        "sdk-tag-pom.xml": "https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/v2.2.0-M1/pom.xml",
    }
    for artifact in ("mcp", "mcp-core", "mcp-json-jackson3"):
        for suffix in (".pom", ".jar", "-sources.jar"):
            name = f"{artifact}-{SDK_VERSION}{suffix}"
            urls[name] = CENTRAL + f"io/modelcontextprotocol/sdk/{artifact}/{SDK_VERSION}/{name}"
    for version in AI_VERSIONS:
        for artifact in ("spring-ai-bom", "spring-ai-mcp", "mcp-spring-webflux",
                         "spring-ai-autoconfigure-mcp-server-common", "spring-ai-autoconfigure-mcp-server-webflux"):
            for suffix in ((".pom",) if artifact == "spring-ai-bom" else (".pom", ".jar", "-sources.jar")):
                name = f"{artifact}-{version}{suffix}"
                urls[name] = CENTRAL + f"org/springframework/ai/{artifact}/{version}/{name}"
    for page in SPEC_PAGES:
        urls["spec-" + page.replace("/", "-") + ".md"] = SPEC + page + ".md"
    return urls


def fetch(item):
    name, url = item
    request = urllib.request.Request(url, headers={"User-Agent": "mcp-gateway-s0-compatibility"})
    with urllib.request.urlopen(request, timeout=25) as response:
        data = response.read()
    target = OUT / name
    target.write_bytes(data)
    if name.endswith("-sources.jar"):
        # Source jars are external inputs: constrain each extracted path explicitly.
        destination = OUT / (name.removesuffix("-sources.jar") + "-sources")
        with zipfile.ZipFile(target) as archive:
            for entry in archive.infolist():
                resolved = (destination / entry.filename).resolve()
                if not resolved.is_relative_to(destination.resolve()):
                    raise ValueError("Source archive path escapes destination")
            archive.extractall(destination)
    return {"file": name, "url": url, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=8) as pool:
        manifest = list(pool.map(fetch, inputs().items()))
    (OUT / "inputs.json").write_text(json.dumps({
        "retrievedAt": datetime.now(timezone.utc).isoformat(), "inputs": manifest,
    }, indent=2) + "\n", encoding="utf-8")
    for file in ("sdk-metadata.xml", "ai-metadata.xml"):
        metadata = ET.parse(OUT / file)
        print(file, "latest=" + metadata.findtext("versioning/latest"),
              "updated=" + metadata.findtext("versioning/lastUpdated"))
    for version in AI_VERSIONS:
        name = f"spring-ai-autoconfigure-mcp-server-common-{version}"
        with zipfile.ZipFile(OUT / (name + ".jar")) as archive:
            data = archive.read("META-INF/spring-configuration-metadata.json")
        (OUT / (name + "-metadata.json")).write_bytes(data)
        properties = [p["name"] for p in json.loads(data).get("properties", [])]
        print(version, "modern properties:", [p for p in properties if "modern" in p])
    modern = OUT / f"mcp-core-{SDK_VERSION}-sources/io/modelcontextprotocol/modern"
    print("modern client source files:", len(list(modern.glob("client/**/*.java"))))
    print("modern HTTP transports:", [p.name for p in modern.glob("server/transport/*Http*.java")])
    print(f"Recorded {len(manifest)} official inputs with SHA-256 digests in {OUT / 'inputs.json'}")


if __name__ == "__main__":
    main()
