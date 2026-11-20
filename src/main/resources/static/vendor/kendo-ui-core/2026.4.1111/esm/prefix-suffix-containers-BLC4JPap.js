/*!
* Copyright 2026 Progress Software Corporation and/or one of its subsidiaries or affiliates. All rights reserved.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
import"./kendo.core-Bc2n2BiN.js";let e=kendo.jQuery;function t({widget:t,wrapper:n,options:r,prefixInsertBefore:i,suffixInsertAfter:a}){var o=r.prefixOptions,s=r.suffixOptions,c=o.template||o.icon,l=s.template||s.icon,a=a||i,u=r.layoutFlow,d=u?u==`vertical`?`horizontal`:`vertical`:`horizontal`,f=`<span class="k-input-separator k-input-separator-${u==`vertical`?`horizontal`:`vertical`}"></span>`,p,m;o&&c&&(p=n.children(`.k-input-prefix`),p[0]||(r._isInInlineAIPrompt&&(d=u||`horizontal`),p=e(`<span class="k-input-prefix k-input-prefix-${d}" />`),i?p.insertBefore(i):p.prependTo(n)),o.icon&&p.html(kendo.html.renderIcon({icon:o.icon,iconClass:o.iconClass})),o.template&&p.html(kendo.template(o.template)({})),o.separator&&e(f).insertAfter(p)),s&&l&&(m=n.children(`.k-input-suffix`),m[0]||(m=e(`<span class="k-input-suffix k-input-suffix-${d}" />`).appendTo(n),a?m.insertAfter(a):m.appendTo(n)),s.icon&&m.html(kendo.html.renderIcon({icon:s.icon,iconClass:s.iconClass})),s.template&&m.html(kendo.template(s.template)({})),s.separator&&e(f).insertBefore(m)),t._prefixContainer=p,t._suffixContainer=m}export{t};
//# sourceMappingURL=prefix-suffix-containers-BLC4JPap.js.map