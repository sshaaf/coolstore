# Generated Artefacts

| Artefact | Location |
|---|---|
| Quarkus build definition | `pom.xml` |
| Runtime configuration | `src/main/resources/application.properties` |
| Migrated Java sources | `src/main/java/com/redhat/coolstore/**` |
| Characterization + unit tests | `src/test/java/com/redhat/coolstore/**` |
| Container image definition | `Containerfile`, `.dockerignore` |
| OpenShift manifests (kustomize) | `deploy/openshift/` |
| Static SPA | `src/main/resources/META-INF/resources/` |
| Post-migration knowledge graph | `.rgctl/` (git-ignored analysis output) |
| Migration log | `mig-execute-workspace/execution-log.md` |

## Verification commands (require a JDK 21 / Maven / Podman / cluster environment)

```bash
mvn verify
podman build -t quay.io/example/coolstore-monolith:1.0.0 .
oc apply -k deploy/openshift
```

## Static verification executed here

```bash
grep -rn --include=*.java "javax.ejb\|javax.jms\|javax.persistence\|javax.ws.rs\|javax.json\|javax.naming" src/main/java   # 0 hits
grep -rn --include=*.java "weblogic\|@MessageDriven\|@Stateless\|@Stateful\|InitialContext\|JMSContext\|@Remote" src/main/java   # 0 hits
rgctl discover . --exclude 'src/main/resources/META-INF/resources/bower_components/**' --with-cfg --with-taint --with-harmonic --export-migration-hints
rgctl -f json rules run rules    # violations: []
```
