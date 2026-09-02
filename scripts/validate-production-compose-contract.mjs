import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

const [
  basePath,
  validationPath,
  baseSourcePath,
  validationSourcePath,
  validationRoot,
  expectedImage,
  expectedTask,
  expectedHead,
] = process.argv.slice(2);

if (
  !basePath ||
  !validationPath ||
  !baseSourcePath ||
  !validationSourcePath ||
  !validationRoot ||
  !expectedImage ||
  !expectedTask ||
  !expectedHead
) {
  throw new Error("production Compose contract validator arguments are incomplete");
}

const [base, validation, baseSource, validationSource] = await Promise.all([
  readConfig(basePath),
  readConfig(validationPath),
  readFile(resolve(baseSourcePath), "utf8"),
  readFile(resolve(validationSourcePath), "utf8"),
]);

validateBindSourceContract(baseSource, "base production Compose");
validateBindSourceContract(validationSource, "production validation overlay");
validateBase(base);
validateValidation(validation);

process.stdout.write(
  `${JSON.stringify(
    {
      contract: "production-compose-v1",
      services: Object.keys(base.services).sort(),
      networks: Object.keys(base.networks).sort(),
      publishedServices: ["rhaomi-web"],
      postgresVolume: base.volumes["postgres-data"].name,
      oneShotMigration: true,
      oneShotSchemaValidation: true,
      backupVerifierReadOnly: true,
      backupVerifierNetworkDisabled: true,
      secretsPrinted: false,
      status: "verified",
    },
    null,
    2,
  )}\n`,
);

async function readConfig(path) {
  const parsed = JSON.parse(await readFile(resolve(path), "utf8"));
  assert.equal(typeof parsed, "object", "rendered Compose object가 필요합니다.");
  return parsed;
}

function validateBindSourceContract(source, subject) {
  const lines = source.split(/\r?\n/u);
  let bindMountCount = 0;

  for (let index = 0; index < lines.length; index += 1) {
    const match = /^(\s*)- type: bind\s*$/u.exec(lines[index]);
    if (!match) {
      continue;
    }

    bindMountCount += 1;
    const itemIndent = match[1].length;
    let end = index + 1;
    while (end < lines.length) {
      const line = lines[end];
      if (line.trim() === "") {
        end += 1;
        continue;
      }
      if (/^\s*/u.exec(line)[0].length <= itemIndent) {
        break;
      }
      end += 1;
    }

    const block = lines.slice(index, end).join("\n");
    assert.match(
      block,
      /^\s+bind:\s*\n\s+create_host_path: false\s*$/mu,
      `${subject}의 모든 bind mount는 create_host_path: false를 명시해야 합니다.`,
    );
    assert.doesNotMatch(
      block,
      /^\s+create_host_path: true\s*$/mu,
      `${subject}의 bind mount가 host path를 자동 생성하면 안 됩니다.`,
    );
  }

  assert.ok(bindMountCount > 0, `${subject}에 검증할 bind mount가 필요합니다.`);
}

function validateBase(config) {
  assert.deepEqual(
    Object.keys(config.services).sort(),
    [
      "backend",
      "backup-tool",
      "backup-verifier",
      "migration",
      "postgres",
      "publisher",
      "rhaomi-web",
      "schema-validate",
    ],
    "base production service inventory가 다릅니다.",
  );
  assert.deepEqual(
    Object.keys(config.networks).sort(),
    ["build-internal", "data-internal", "loopback-edge", "web-backend"],
    "base production network inventory가 다릅니다.",
  );
  assert.deepEqual(Object.keys(config.volumes), ["postgres-data"]);
  assert.equal(
    config.volumes["postgres-data"].name,
    `${config.name}_postgres-data`,
    "PostgreSQL volume은 Compose project-scoped authority여야 합니다.",
  );
  assert.equal(config.volumes["postgres-data"].external, undefined);

  const {
    backend,
    "backup-tool": backupTool,
    "backup-verifier": backupVerifier,
    migration,
    postgres,
    publisher,
    "rhaomi-web": web,
    "schema-validate": schemaValidate,
  } = config.services;
  assert.equal(backend.image, expectedImage);
  assert.equal(publisher.image, expectedImage);
  assert.equal(migration.image, expectedImage);
  assert.equal(schemaValidate.image, expectedImage);
  assert.equal(backupTool.image, expectedImage);
  assert.equal(backupVerifier.image, expectedImage);
  assert.match(web.image, /^nginx:[^@]+@sha256:[0-9a-f]{64}$/u);
  assert.match(postgres.image, /^postgres:18\.6-[^@]+@sha256:[0-9a-f]{64}$/u);
  assert.equal(web.labels?.["homeops.managed"], "true");
  for (const service of [
    backend,
    publisher,
    migration,
    schemaValidate,
    backupTool,
    backupVerifier,
    postgres,
  ]) {
    assert.equal(
      service.labels?.["homeops.managed"],
      undefined,
      "writable mount 또는 protected service는 HomeOps control 대상이면 안 됩니다.",
    );
  }
  assert.equal(web.user, "101:101", "production Nginx는 non-root UID/GID로 실행해야 합니다.");
  assert.deepEqual(web.tmpfs, [
    "/var/cache/nginx:rw,noexec,nosuid,size=64m,uid=101,gid=101,mode=0750",
    "/var/run:rw,noexec,nosuid,size=8m,uid=101,gid=101,mode=0750",
    "/tmp:rw,noexec,nosuid,size=8m,uid=101,gid=101,mode=0750",
  ]);

  for (const service of Object.values(config.services)) {
    assert.equal(service.build, undefined, "production build directive는 금지됩니다.");
    assert.equal(service.privileged, undefined, "privileged production service는 금지됩니다.");
    assert.notEqual(service.network_mode, "host", "host network는 금지됩니다.");
    assert.equal(service.pid, undefined, "host PID namespace는 금지됩니다.");
    assert.equal(service.ipc, undefined, "host IPC namespace는 금지됩니다.");
    assert.equal(service.devices, undefined, "production device grant는 금지됩니다.");
    for (const mount of service.volumes ?? []) {
      assert.notEqual(mount.source, "/var/run/docker.sock", "Docker socket mount는 금지됩니다.");
    }
    validateLogging(service);
  }

  assert.deepEqual(networks(web), ["loopback-edge", "web-backend"]);
  assert.deepEqual(networks(backend), ["build-internal", "data-internal", "web-backend"]);
  assert.deepEqual(networks(publisher), ["build-internal", "data-internal"]);
  assert.deepEqual(networks(migration), ["data-internal"]);
  assert.deepEqual(networks(schemaValidate), ["data-internal"]);
  assert.deepEqual(networks(postgres), ["data-internal"]);
  assert.deepEqual(networks(backupTool), []);
  assert.deepEqual(networks(backupVerifier), []);
  assert.equal(backupTool.network_mode, "none");
  assert.equal(backupVerifier.network_mode, "none");
  assert.equal(config.networks["loopback-edge"].internal ?? false, false);
  for (const networkName of ["web-backend", "build-internal", "data-internal"]) {
    const network = config.networks[networkName];
    assert.equal(network.internal, true, "production network는 internal-only여야 합니다.");
  }

  assert.equal(web.ports?.length, 1);
  assert.deepEqual(
    pick(web.ports[0], ["host_ip", "target", "protocol"]),
    { host_ip: "127.0.0.1", target: 8080, protocol: "tcp" },
  );
  assert.match(String(web.ports[0].published), /^[1-9][0-9]{0,4}$/u);
  for (const service of [
    backend,
    publisher,
    migration,
    schemaValidate,
    backupTool,
    backupVerifier,
    postgres,
  ]) {
    assert.equal(service.ports, undefined, "web 외 host port는 금지됩니다.");
  }

  assert.deepEqual(backend.command, ["java", "-jar", "/opt/rhaomi/backend.jar"]);
  assert.deepEqual(publisher.command, [
    "java",
    "-jar",
    "/opt/rhaomi/backend.jar",
    "--rhaomi.publisher.mode=control-loop",
  ]);
  assert.deepEqual(migration.command, [
    "java",
    "-jar",
    "/opt/rhaomi/backend.jar",
    "--rhaomi.production-task=migrate",
  ]);
  assert.deepEqual(schemaValidate.command, [
    "java",
    "-jar",
    "/opt/rhaomi/backend.jar",
    "--rhaomi.production-task=schema-validate",
  ]);
  assert.deepEqual(migration.profiles, ["production-task"]);
  assert.deepEqual(schemaValidate.profiles, ["production-task"]);
  assert.deepEqual(backupTool.command, [
    "node",
    "/opt/rhaomi/source/scripts/rhaomi-backup-tool.mjs",
  ]);
  assert.deepEqual(backupTool.profiles, ["production-backup"]);
  assert.deepEqual(backupVerifier.profiles, ["production-backup"]);
  assert.deepEqual(backupVerifier.entrypoint, ["/usr/local/bin/rhaomi-backup-verifier"]);
  assert.deepEqual(backupVerifier.command, ["verify-eligibility"]);

  validateEnvironmentBoundary(
    web,
    backend,
    publisher,
    migration,
    schemaValidate,
    backupTool,
    backupVerifier,
    postgres,
  );
  validateBaseMounts(
    web,
    backend,
    publisher,
    migration,
    schemaValidate,
    backupTool,
    backupVerifier,
    postgres,
  );
  for (const service of [
    web,
    backend,
    publisher,
    migration,
    schemaValidate,
    backupTool,
    backupVerifier,
  ]) {
    assert.equal(service.read_only, true, "application container root는 read-only여야 합니다.");
    assert.deepEqual(service.cap_drop, ["ALL"]);
    assert.deepEqual(service.security_opt, ["no-new-privileges:true"]);
  }
}

function validateValidation(config) {
  assert.deepEqual(
    Object.keys(config.services).sort(),
    [
      "backend",
      "backup-permission",
      "backup-tool",
      "backup-verifier",
      "migration",
      "postgres",
      "publisher",
      "rhaomi-web",
      "schema-validate",
    ],
  );
  assert.equal(config.services.backend.image, expectedImage);
  assert.equal(config.services.publisher.image, expectedImage);
  assert.equal(config.services.migration.image, expectedImage);
  assert.equal(config.services["schema-validate"].image, expectedImage);
  assert.equal(config.services["backup-tool"].image, expectedImage);
  assert.equal(config.services["backup-verifier"].image, expectedImage);
  assert.equal(config.services["backup-permission"].image, expectedImage);
  assert.equal(config.services.migration.environment.SPRING_FLYWAY_ENABLED, "true");
  assert.equal(
    config.services.migration.environment.RHAOMI_BOOTSTRAP_ADMIN_ENABLED,
    "false",
  );
  assert.equal(
    config.services["schema-validate"].environment.SPRING_FLYWAY_ENABLED,
    "false",
  );
  assert.equal(config.services.migration.environment.SPRING_JPA_HIBERNATE_DDL_AUTO, "validate");
  assert.equal(
    config.services["schema-validate"].environment.SPRING_JPA_HIBERNATE_DDL_AUTO,
    "validate",
  );
  assert.deepEqual(networks(config.services.migration), ["data-internal"]);
  assert.deepEqual(networks(config.services["schema-validate"]), ["data-internal"]);
  assert.deepEqual(networks(config.services["backup-permission"]), []);
  assert.deepEqual(networks(config.services["backup-verifier"]), []);
  assert.deepEqual(config.services["backup-permission"].profiles, ["production-backup"]);
  assert.equal(config.services["backup-permission"].user, "0:0");
  assert.equal(config.services["backup-permission"].read_only, true);
  assert.deepEqual(config.services["backup-permission"].cap_drop, ["ALL"]);
  assert.deepEqual(config.services["backup-permission"].cap_add, [
    "CHOWN",
    "DAC_OVERRIDE",
    "FOWNER",
  ]);
  assert.deepEqual(config.services["backup-permission"].security_opt, [
    "no-new-privileges:true",
  ]);
  assert.equal(config.services["backup-permission"].network_mode, "none");
  assert.deepEqual(config.services["backup-permission"].entrypoint, [
    "/usr/local/bin/rhaomi-backup-media-permissions",
  ]);
  assert.deepEqual(config.services["backup-permission"].command, [
    "assert-runtime",
    "0",
    "0",
  ]);
  assert.deepEqual(config.services["backup-permission"].environment ?? {}, {});

  const root = resolve(validationRoot);
  const expectedMounts = {
    "rhaomi-web": {
      "/etc/nginx/conf.d/default.conf": `${root}/app/nginx/production.conf`,
      "/srv/rhaomi/public": `${root}/public`,
    },
    backend: { "/var/lib/rhaomi/media": `${root}/data/media` },
    publisher: {
      "/srv/rhaomi/public": `${root}/public`,
      "/var/lib/rhaomi/media": `${root}/data/media`,
      "/var/lib/rhaomi/publisher": `${root}/state/publisher`,
      "/opt/rhaomi/source/.rhaomi-publication-work": `${root}/state/publisher/build-workspace`,
      "/var/lib/rhaomi/locks": `${root}/state/locks`,
    },
    "backup-tool": {
      "/var/lib/rhaomi/backup-repository": `${root}/backup-repository`,
      "/var/lib/rhaomi/media": `${root}/data/media`,
      "/var/lib/rhaomi/deploy-state": `${root}/state/deploy`,
      "/var/lib/rhaomi/restore-media": `${root}/restore-media`,
    },
    "backup-verifier": {
      "/var/lib/rhaomi/backup-repository": `${root}/backup-repository`,
      "/var/lib/rhaomi/deploy-state": `${root}/state/deploy`,
    },
    "backup-permission": {
      "/var/lib/rhaomi/media-permissions": `${root}/data/media`,
    },
  };
  for (const [serviceName, mounts] of Object.entries(expectedMounts)) {
    const service = config.services[serviceName];
    assert.deepEqual(
      Object.fromEntries((service.volumes ?? []).map((mount) => [mount.target, mount.source])),
      mounts,
      `${serviceName} validation mount source가 다릅니다.`,
    );
    for (const mount of service.volumes ?? []) {
      assert.equal(mount.type, "bind");
      assert.notEqual(
        mount.bind?.create_host_path,
        true,
        "rendered validation bind mount가 host path를 자동 생성하면 안 됩니다.",
      );
      assert.ok(mount.source.startsWith(`${root}/`));
      assert.ok(!mount.source.startsWith("/private/var/lib/rhaomi/"));
    }
  }

  assert.equal(mount(config.services["rhaomi-web"], "/srv/rhaomi/public").read_only, true);
  assert.equal(mount(config.services.backend, "/var/lib/rhaomi/media").read_only, undefined);
  assert.equal(mount(config.services.publisher, "/srv/rhaomi/public").read_only, undefined);
  assert.equal(mount(config.services.publisher, "/var/lib/rhaomi/media").read_only, true);
  assert.equal(mount(config.services.publisher, "/var/lib/rhaomi/publisher").read_only, undefined);
  assert.equal(mount(config.services.publisher, "/var/lib/rhaomi/locks").read_only, undefined);
  assert.equal(
    mount(config.services["backup-tool"], "/var/lib/rhaomi/backup-repository").read_only,
    undefined,
  );
  assert.equal(mount(config.services["backup-tool"], "/var/lib/rhaomi/media").read_only, true);
  assert.equal(
    mount(config.services["backup-tool"], "/var/lib/rhaomi/deploy-state").read_only,
    undefined,
  );
  assert.equal(
    mount(config.services["backup-verifier"], "/var/lib/rhaomi/backup-repository")
      .read_only,
    true,
  );
  assert.equal(
    mount(config.services["backup-verifier"], "/var/lib/rhaomi/deploy-state").read_only,
    true,
  );
  assert.equal(
    config.services["backup-verifier"].volumes.some(
      (item) => item.target === "/var/lib/rhaomi/media",
    ),
    false,
  );
  assert.equal(
    mount(config.services["backup-permission"], "/var/lib/rhaomi/media-permissions")
      .read_only,
    undefined,
  );

  for (const [serviceName, service] of Object.entries(config.services)) {
    validateLabels(
      service.labels,
      `${serviceName} labels`,
      serviceName === "rhaomi-web" ? { "homeops.managed": "true" } : {},
    );
  }
  validateLabels(config.volumes["postgres-data"].labels, "PostgreSQL volume labels");
  for (const [networkName, network] of Object.entries(config.networks)) {
    validateLabels(network.labels, `${networkName} labels`);
  }
}

function validateEnvironmentBoundary(
  web,
  backend,
  publisher,
  migration,
  schemaValidate,
  backupTool,
  backupVerifier,
  postgres,
) {
  assert.deepEqual(web.environment ?? {}, {});
  assert.equal(backend.environment.SPRING_FLYWAY_ENABLED, "false");
  assert.equal(backend.environment.RHAOMI_SESSION_COOKIE_SECURE, "true");
  assert.equal(backend.environment.RHAOMI_BOOTSTRAP_ADMIN_ENABLED, "false");
  assert.equal(backend.environment.RHAOMI_ADMIN_WEBAUTHN_REQUIRED, "true");
  assert.equal(backend.environment.RHAOMI_WEBAUTHN_RP_ID, "validation.invalid");
  assert.equal(
    backend.environment.RHAOMI_WEBAUTHN_ORIGIN,
    "https://validation.invalid",
  );
  assert.equal(backend.environment.RHAOMI_WEBAUTHN_CHALLENGE_TTL, "5m");
  assert.equal(publisher.environment.SPRING_FLYWAY_ENABLED, "false");
  assert.equal(migration.environment.SPRING_FLYWAY_ENABLED, "true");
  assert.equal(schemaValidate.environment.SPRING_FLYWAY_ENABLED, "false");
  assert.equal(migration.environment.SPRING_JPA_HIBERNATE_DDL_AUTO, "validate");
  assert.equal(schemaValidate.environment.SPRING_JPA_HIBERNATE_DDL_AUTO, "validate");
  assert.equal(publisher.environment.BUILD_API_INTERNAL_URL, "http://backend:8080");
  assert.equal(
    publisher.environment.BUILD_API_CREDENTIAL,
    backend.environment.RHAOMI_BUILD_SERVICE_TOKEN,
    "backend와 publisher credential authority가 일치해야 합니다.",
  );
  assert.equal(publisher.environment.RHAOMI_BUILD_SERVICE_TOKEN, undefined);
  assert.equal(backend.environment.BUILD_API_CREDENTIAL, undefined);
  assert.equal(postgres.environment.RHAOMI_BUILD_SERVICE_TOKEN, undefined);
  assert.equal(postgres.environment.BUILD_API_CREDENTIAL, undefined);
  for (const service of [web, publisher, postgres, migration, schemaValidate]) {
    assert.equal(service.environment?.RHAOMI_WEBAUTHN_RP_ID, undefined);
    assert.equal(service.environment?.RHAOMI_WEBAUTHN_ORIGIN, undefined);
    assert.equal(service.environment?.RHAOMI_WEBAUTHN_RP_NAME, undefined);
  }
  for (const service of [migration, schemaValidate]) {
    assert.equal(service.environment.RHAOMI_BUILD_SERVICE_TOKEN, undefined);
    assert.equal(service.environment.BUILD_API_CREDENTIAL, undefined);
  }
  assert.deepEqual(backupTool.environment, {
    RHAOMI_BACKUP_DEPLOY_STATE_ROOT: "/var/lib/rhaomi/deploy-state",
    RHAOMI_BACKUP_MEDIA_ROOT: "/var/lib/rhaomi/media",
    RHAOMI_BACKUP_REPOSITORY_ROOT: "/var/lib/rhaomi/backup-repository",
  });
  assert.deepEqual(backupVerifier.environment, {
    RHAOMI_BACKUP_DEPLOY_STATE_ROOT: "/var/lib/rhaomi/deploy-state",
    RHAOMI_BACKUP_REPOSITORY_ROOT: "/var/lib/rhaomi/backup-repository",
  });
  for (const variableName of [
    "RHAOMI_BACKUP_MEDIA_ROOT",
    "RHAOMI_BACKUP_RESTORE_MEDIA_ROOT",
    "RHAOMI_BUILD_SERVICE_TOKEN",
    "BUILD_API_CREDENTIAL",
    "SPRING_DATASOURCE_PASSWORD",
  ]) {
    assert.equal(backupVerifier.environment[variableName], undefined);
  }
  for (const service of [web, postgres]) {
    assert.equal(service.environment?.SPRING_DATASOURCE_PASSWORD, undefined);
  }
}

function validateBaseMounts(
  web,
  backend,
  publisher,
  migration,
  schemaValidate,
  backupTool,
  backupVerifier,
  postgres,
) {
  assert.deepEqual(mountMap(web), {
    "/etc/nginx/conf.d/default.conf": "/private/var/lib/rhaomi/app/nginx/production.conf",
    "/srv/rhaomi/public": "/private/var/lib/rhaomi/public",
  });
  assert.deepEqual(mountMap(backend), {
    "/var/lib/rhaomi/media": "/private/var/lib/rhaomi/data/media",
  });
  assert.deepEqual(mountMap(publisher), {
    "/srv/rhaomi/public": "/private/var/lib/rhaomi/public",
    "/var/lib/rhaomi/media": "/private/var/lib/rhaomi/data/media",
    "/var/lib/rhaomi/publisher": "/private/var/lib/rhaomi/state/publisher",
    "/opt/rhaomi/source/.rhaomi-publication-work":
      "/private/var/lib/rhaomi/state/publisher/build-workspace",
    "/var/lib/rhaomi/locks": "/private/var/lib/rhaomi/state/locks",
  });
  assert.deepEqual(postgres.volumes, [
    { type: "volume", source: "postgres-data", target: "/var/lib/postgresql" },
  ]);
  assert.equal(migration.volumes, undefined);
  assert.equal(schemaValidate.volumes, undefined);
  assert.deepEqual(mountMap(backupTool), {
    "/var/lib/rhaomi/backup-repository": resolve(validationRoot, "backup-repository"),
    "/var/lib/rhaomi/media": "/private/var/lib/rhaomi/data/media",
    "/var/lib/rhaomi/deploy-state": "/private/var/lib/rhaomi/state/deploy",
  });
  assert.deepEqual(mountMap(backupVerifier), {
    "/var/lib/rhaomi/backup-repository": resolve(validationRoot, "backup-repository"),
    "/var/lib/rhaomi/deploy-state": "/private/var/lib/rhaomi/state/deploy",
  });
  for (const service of [web, backend, publisher, backupTool, backupVerifier]) {
    for (const item of service.volumes) {
      assert.equal(item.type, "bind");
      assert.notEqual(
        item.bind?.create_host_path,
        true,
        "rendered production bind mount가 host path를 자동 생성하면 안 됩니다.",
      );
    }
  }
  assert.equal(mount(web, "/srv/rhaomi/public").read_only, true);
  assert.equal(mount(backend, "/var/lib/rhaomi/media").read_only, undefined);
  assert.equal(mount(publisher, "/srv/rhaomi/public").read_only, undefined);
  assert.equal(mount(publisher, "/var/lib/rhaomi/media").read_only, true);
  assert.equal(mount(backupTool, "/var/lib/rhaomi/media").read_only, true);
  assert.equal(
    mount(backupVerifier, "/var/lib/rhaomi/backup-repository").read_only,
    true,
  );
  assert.equal(mount(backupVerifier, "/var/lib/rhaomi/deploy-state").read_only, true);
}

function validateLabels(labels, subject, sourceLabels = {}) {
  assert.deepEqual(
    labels,
    {
      ...sourceLabels,
      "io.homeserver.cleanup.environment": "development",
      "io.homeserver.cleanup.git-head": expectedHead,
      "io.homeserver.cleanup.lifecycle": "task",
      "io.homeserver.cleanup.project": "rhaomi",
      "io.homeserver.cleanup.retain": "false",
      "io.homeserver.cleanup.task": expectedTask,
    },
    `${subject}가 task resource contract와 다릅니다.`,
  );
}

function validateLogging(service) {
  assert.deepEqual(service.logging, {
    driver: "local",
    options: { "max-file": "5", "max-size": "20m" },
  });
}

function networks(service) {
  return Object.keys(service.networks ?? {}).sort();
}

function mount(service, target) {
  const found = (service.volumes ?? []).find((item) => item.target === target);
  assert.ok(found, `${target} mount가 필요합니다.`);
  return found;
}

function mountMap(service) {
  return Object.fromEntries(service.volumes.map((item) => [item.target, item.source]));
}

function pick(object, keys) {
  return Object.fromEntries(keys.map((key) => [key, object[key]]));
}
