/**
 * cs-spinner 组件
 *
 * 用法:
 *   import { Spinner } from "./components/cs-spinner.js";
 *   const s = new Spinner({ size: "sm" });
 *   element.appendChild(s.el);
 */

export class Spinner {
    constructor(opts = {}) {
        this.el = document.createElement("span");
        this.el.className = `cs-spinner size-${opts.size || "md"}`;
        this.el.setAttribute("aria-label", "loading");
    }
}
