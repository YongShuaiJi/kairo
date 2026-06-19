import openapiTS from "openapi-typescript";
import { astToString } from "openapi-typescript";
import { readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import YAML from "yaml";

const here = dirname(fileURLToPath(import.meta.url));
const source = resolve(here, "../../docs/api/platform-openapi.yaml");
const target = resolve(here, "../lib/api/schema.d.ts");
const document = YAML.parse(await readFile(source, "utf8"));
const ast = await openapiTS(document);
await writeFile(target, `// Generated from docs/api/platform-openapi.yaml\n${astToString(ast)}`);
