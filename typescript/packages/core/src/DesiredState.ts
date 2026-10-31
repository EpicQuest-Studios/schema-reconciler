import { readFileSync } from "node:fs";
import { DesiredStateException } from "./DesiredStateException.ts";

/**
 * The desired state declared as a SQL script (spec 3.2). The script text is
 * read once, as UTF-8.
 */
export class DesiredState {
    readonly script: string;

    constructor(script: string) {
        if (script == null) {
            throw new TypeError("script is required");
        }
        this.script = script;
        Object.freeze(this);
    }

    static ofScript(sql: string): DesiredState {
        return new DesiredState(sql);
    }

    static ofPath(path: string): DesiredState {
        if (path == null) {
            throw new TypeError("path is required");
        }
        try {
            return new DesiredState(readFileSync(path, "utf8"));
        } catch (e) {
            if (e instanceof TypeError) {
                throw e;
            }
            throw new DesiredStateException("could not read script: " + path, e);
        }
    }
}
