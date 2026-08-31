import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

const [basePath, validationPath, validationRoot, expectedImage, expectedTask, expectedHead] =
  process.argv.slice(2);

if (
  !basePath ||
  !validationPath ||
  !validationRoot ||
  !expectedImage ||
  !expectedTask ||
  !expectedHead
) {
  throw new Error("production Compose contract validator arguments are incomplete");
}

const [base, validation] = await Promise.all([
  readConfig(basePath),
  readConfig(validationPath),
]);

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
      validationSchemaBootstrap: true,
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

function validateBase(config) {
  assert.deepEqual(
    Object.keys(config.services).sort(),
    ["backend", "postgres", "publisher", "rhaomi-web"],
    "base production service inventory가 다릅니다.",
  );
  assert.deepEqual(
    Object.keys(config.networks).sort(),
    ["build-internal", "data-internal", "web-backend"],
    "base production network inventory가 다릅니다.",
  );
  assert.deepEqual(Object.keys(config.volumes), ["postgres-data"]);
  assert.equal(
    config.volumes["postgres-data"].name,
    `${config.name}_postgres-data`,
    "PostgreSQL volume은 Compose project-scoped authority여야 합니다.",
  );
  assert.equal(config.volumes["postgres-data"].external, undefined);

  const { backend, postgres, publisher, "rhaomi-web": web } = config.services;
  assert.equal(backend.image, expectedImage);
  assert.equal(publisher.image, expectedImage);
  assert.match(web.image, /^nginx:[^@]+@sha256:[0-9a-f]{64}$/u);
  assert.match(postgres.image, /^postgres:18\.6-[^@]+@sha256:[0-9a-f]{64}$/u);

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

  assert.deepEqual(networks(web), ["web-backend"]);
  assert.deepEqual(networks(backend), ["build-internal", "data-internal", "web-backend"]);
  assert.deepEqual(networks(publisher), ["build-internal", "data-internal"]);
  assert.deepEqual(networks(postgres), ["data-internal"]);
  for (const network of Object.values(config.networks)) {
    assert.equal(network.internal, true, "production network는 internal-only여야 합니다.");
  }

  assert.equal(web.ports?.length, 1);
  assert.deepEqual(
    pick(web.ports[0], ["host_ip", "target", "protocol"]),
    { host_ip: "127.0.0.1", target: 8080, protocol: "tcp" },
  );
  assert.match(String(web.ports[0].published), /^[1-9][0-9]{0,4}$/u);
  for (const service of [backend, publisher, postgres]) {
    assert.equal(service.ports, undefined, "web 외 host port는 금지됩니다.");
  }

  assert.deepEqual(backend.command, ["java", "-jar", "/opt/rhaomi/backend.jar"]);
  assert.deepEqual(publisher.command, [
    "java",
    "-jar",
    "/opt/rhaomi/backend.jar",
    "--rhaomi.publisher.mode=control-loop",
  ]);

  validateEnvironmentBoundary(web, backend, publisher, postgres);
  validateBaseMounts(web, backend, publisher, postgres);
  for (const service of [web, backend, publisher]) {
    assert.equal(service.read_only, true, "application container root는 read-only여야 합니다.");
    assert.deepEqual(service.cap_drop, ["ALL"]);
    assert.deepEqual(service.security_opt, ["no-new-privileges:true"]);
  }
}

function validateValidation(config) {
  assert.deepEqual(
    Object.keys(config.services).sort(),
    ["backend", "postgres", "publisher", "rhaomi-web", "schema-bootstrap"],
  );
  assert.equal(config.services.backend.image, expectedImage);
  assert.equal(config.services.publisher.image, expectedImage);
  assert.equal(config.services["schema-bootstrap"].image, expectedImage);
  assert.equal(config.services["schema-bootstrap"].environment.SPRING_FLYWAY_ENABLED, "true");
  assert.equal(
    config.services["schema-bootstrap"].environment.RHAOMI_BOOTSTRAP_ADMIN_ENABLED,
    "false",
  );
  assert.equal(
    config.services["schema-bootstrap"].environment.RHAOMI_SESSION_COOKIE_SECURE,
    "true",
  );
  assert.deepEqual(networks(config.services["schema-bootstrap"]), ["data-internal"]);

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
      "/var/lib/rhaomi/locks": `${root}/state/locks`,
    },
    "schema-bootstrap": { "/var/lib/rhaomi/media": `${root}/data/media` },
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
      assert.equal(mount.bind?.create_host_path, false);
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

  for (const [serviceName, service] of Object.entries(config.services)) {
    validateLabels(service.labels, `${serviceName} labels`);
  }
  validateLabels(config.volumes["postgres-data"].labels, "PostgreSQL volume labels");
  for (const [networkName, network] of Object.entries(config.networks)) {
    validateLabels(network.labels, `${networkName} labels`);
  }
}

function validateEnvironmentBoundary(web, backend, publisher, postgres) {
  assert.deepEqual(web.environment ?? {}, {});
  assert.equal(backend.environment.SPRING_FLYWAY_ENABLED, "false");
  assert.equal(backend.environment.RHAOMI_SESSION_COOKIE_SECURE, "true");
  assert.equal(backend.environment.RHAOMI_BOOTSTRAP_ADMIN_ENABLED, "false");
  assert.equal(publisher.environment.SPRING_FLYWAY_ENABLED, "false");
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
  for (const service of [web, postgres]) {
    assert.equal(service.environment?.SPRING_DATASOURCE_PASSWORD, undefined);
  }
}

function validateBaseMounts(web, backend, publisher, postgres) {
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
    "/var/lib/rhaomi/locks": "/private/var/lib/rhaomi/state/locks",
  });
  assert.deepEqual(postgres.volumes, [
    { type: "volume", source: "postgres-data", target: "/var/lib/postgresql" },
  ]);
  for (const service of [web, backend, publisher]) {
    for (const item of service.volumes) {
      assert.equal(item.type, "bind");
      assert.equal(item.bind?.create_host_path, false);
    }
  }
  assert.equal(mount(web, "/srv/rhaomi/public").read_only, true);
  assert.equal(mount(backend, "/var/lib/rhaomi/media").read_only, undefined);
  assert.equal(mount(publisher, "/srv/rhaomi/public").read_only, undefined);
  assert.equal(mount(publisher, "/var/lib/rhaomi/media").read_only, true);
}

function validateLabels(labels, subject) {
  assert.deepEqual(
    labels,
    {
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
