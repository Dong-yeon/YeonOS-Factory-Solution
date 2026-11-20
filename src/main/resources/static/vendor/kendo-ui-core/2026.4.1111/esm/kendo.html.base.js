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
import"./kendo.core-Bc2n2BiN.js";const e={id:`html.base`,name:`Html.Base`,category:`web`,description:``,depends:[`core`],features:[]};(function(e,t){var n=window.kendo,r=n.Class;n.html=n.html||{};var i=r.extend({init:function(t,n){var r=this;r.element=e(t),n||={},delete n.name,r._initOptions(n)},options:{stylingOptions:[]},_addClasses:function(){var e=this,t=e.options,r=t.stylingOptions,i=e.wrapper.data(`added-classes`);r=r.map(function(e){return n.cssProperties.getValidClass({widget:t.name,propName:e,value:t[e]})}),i&&e.wrapper.removeClass(i.join(` `)),e.wrapper.data(`added-classes`,r),e.wrapper.addClass(r.join(` `))},html:function(){return this.wrapper[0].outerHTML}});e.extend(n.html,{HTMLBase:i})})(window.kendo.jQuery);var t=kendo;export{e as __meta__,t as default};
//# sourceMappingURL=kendo.html.base.js.map