#!/usr/bin/env python3
"""Verify the packaged Java artifact in a separate, production-only Java consumer.

First run sbt 'classfile/packageBin' 'classfile/makePom' 'show classfile/Runtime/fullClasspath'.
"""
from pathlib import Path
import os
import subprocess
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[2]
target = root / "classfile/target"
jar = target / "konsume-classfile-0.1.0-SNAPSHOT.jar"
export = target / "streams/runtime/fullClasspath/_global/streams/export"
dependencies = [p for p in export.read_text().strip().split(os.pathsep) if p.endswith(".jar")]
assert dependencies and all("scala-" not in p and "jmh-" not in p for p in dependencies)
classpath = os.pathsep.join([str(jar), *dependencies])
with zipfile.ZipFile(jar) as archive:
    assert int.from_bytes(archive.read("konsume/classfile/AvroFilter.class")[6:8], "big") == 68
    for name in archive.namelist():
        assert not any(word in name for word in ("Restored", "Baseline", "Investigation", "Peeled",
                                                "FastAvro", "EarlyReturn", "Handwritten", "scala/", "org/openjdk/jmh"))
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
pom = ET.parse(target / "konsume-classfile-0.1.0-SNAPSHOT.pom")
production = [(d.findtext("m:groupId", namespaces=ns), d.findtext("m:artifactId", namespaces=ns))
              for d in pom.findall(".//m:dependency", ns)
              if d.findtext("m:scope", default="compile", namespaces=ns) != "test"]
assert production == [("org.apache.avro", "avro")], production
smoke_classes = root / "bench/target/embedding-smoke"
smoke_classes.mkdir(parents=True, exist_ok=True)
subprocess.run(["javac", "--release", "24", "-cp", classpath, "-d", str(smoke_classes),
                str(root / "bench/src/smoke/EmbeddingSmoke.java")], check=True)
subprocess.run(["java", "-cp", str(smoke_classes) + os.pathsep + classpath, "EmbeddingSmoke"], check=True)
print(f"Packaged JAR: {jar.stat().st_size:,} bytes; Java 24 class version; Avro-only direct production dependency")
