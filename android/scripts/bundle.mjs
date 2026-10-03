import { build } from "esbuild";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { dirname, resolve, join } from "node:path";
import { fileURLToPath } from "node:url";

const project = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const pi = resolve(process.env.PI_ROOT ?? join(project, ".."));
const pin = JSON.parse(readFileSync(join(project, "pi-source.json"), "utf8"));
const head = execFileSync("git", ["-C", pi, "rev-parse", "HEAD"], { encoding: "utf8" }).trim();
// Android commits may advance HEAD while the Pi runtime remains at its validated revision.
const runtimeInputs = ["packages/ai", "packages/chord", "packages/durable", "packages/telemetry", "package.json", "package-lock.json"];
execFileSync("git", ["-C", pi, "merge-base", "--is-ancestor", pin.commit, "HEAD"]);
try {
  execFileSync("git", ["-C", pi, "diff", "--quiet", pin.commit, "--", ...runtimeInputs]);
} catch {
  throw new Error(`Pi runtime inputs differ from ${pin.commit}. Review and update pi-source.json deliberately.`);
}
if (!existsSync(join(pi, "node_modules"))) throw new Error("Run npm ci --ignore-scripts in the Pi checkout first.");

const output = join(project, "app/src/main/assets");
mkdirSync(output, { recursive: true });
const packages = { chord: "chord", "pi-ai": "ai", "pi-durable": "durable", "pi-telemetry": "telemetry" };
const sourcePlugin = {
  name: "pi-checkout",
  setup(builder) {
    builder.onResolve({ filter: /^pi-source\// }, ({ path }) => ({ path: join(pi, "packages/ai/src", path.slice(10) + ".ts") }));
    builder.onResolve({ filter: /^@earendil-works\// }, ({ path }) => {
      const [name, ...rest] = path.slice("@earendil-works/".length).split("/");
      const folder = packages[name];
      if (!folder) return undefined;
      const base = join(pi, "packages", folder, "src", rest.join("/"));
      return { path: rest.length && existsSync(base + ".ts") ? base + ".ts" : join(base, "index.ts") };
    });
    builder.onResolve({ filter: /^\.\/data\/.*\.json$/ }, ({ path, importer }) => {
      if (!importer.includes("/packages/ai/src/providers/")) return undefined;
      return { path: join(project, "node_modules/@earendil-works/pi-ai/dist/providers", path) };
    });
  },
};
await build({
  absWorkingDir: project, entryPoints: ["runtime/index.ts"], outfile: join(output, "pi-runtime.cjs"),
  bundle: true, platform: "node", target: "node22.19", format: "cjs",
  sourcemap: false, legalComments: "eof", metafile: true,
  nodePaths: [join(pi, "node_modules")], plugins: [sourcePlugin],
}).then(result => {
  mkdirSync(join(project, "dist"), { recursive: true });
  writeFileSync(join(project, "dist/bundle-meta.json"), JSON.stringify(result.metafile));
});
writeFileSync(join(output, "build-info.json"), JSON.stringify({ piCommit: pin.commit, appCommit: head, javet: "6.0.1", catalog: pin.catalogPackage }));
console.log(`Bundled Pi ${pin.commit.slice(0, 12)} for embedded Node.`);
