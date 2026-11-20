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
import"./kendo.core-Bc2n2BiN.js";import"./kendo.floatinglabel.js";const e={id:`label`,name:`Label`,category:`framework`,description:`Abstraction of label rendering for inputs`,depends:[`core`,`floatinglabel`],hidden:!0},t=window.kendo,n=t.jQuery,r=t.ui.Widget,i=t.isFunction;var a=r.extend({options:{name:`Label`,widget:null},init:function(e,t){var i=this;e||=n(`<label></label>`),r.fn.init.call(i,e,t),t=n.extend(!0,{},t),i.widget=t.widget,t.floating&&i._floatingLabel(),i._label(),r.fn.endInit.call(i)},destroy:function(){this.floatingLabel&&this.floatingLabel.destroy(),r.fn.destroy.call(this)},_unwrapFloating:function(){var e=this;e.floatingLabel&&(e.floatingLabel.destroy(),e.widget.wrapper.unwrap(e._floatingLabelContainer))},setOptions:function(e){var t=this,i=!1;(typeof e==`string`||n.isPlainObject(e)&&e.floating===!1)&&(i=!0),e=n.isPlainObject(e)?e:{content:e},r.fn.setOptions.call(t,e),i&&t.floatingLabel?(t._unwrapFloating(),t.floatingLabel.destroy(),delete t.floatingLabel):e.floating===!0&&!t.floatingLabel&&(t.element.remove(),t._floatingLabel()),t._label()},_label:function(){var e=this,n=e.widget.element,r=e.options,a=n.attr(`id`),o=r.content,s=r.floating||!1;i(o)&&(o=o.call(e)),o||=``,a||(a=r.name+`_`+t.guid(),n.attr(`id`,a)),e.element.addClass(s?`k-floating-label`:`k-label k-input-label`).attr(`for`,a).text(o)[s?`insertAfter`:`insertBefore`](e.options.beforeElm||e.widget.wrapper),e.floatingLabel&&e.floatingLabel.refresh()},_floatingLabel:function(){var e=this,r=n.extend({},e.options),i;delete r.name,i=r.floating||!1,i&&(e._floatingLabelContainer=e.widget.wrapper.wrap(`<span></span>`).parent(),e.floatingLabel=new t.ui.FloatingLabel(e._floatingLabelContainer,n.extend({},r)))}});t.ui.plugin(a);export{e as __meta__,t as default};
//# sourceMappingURL=kendo.label.js.map