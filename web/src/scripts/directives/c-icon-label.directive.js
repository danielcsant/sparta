(function () {
'use strict';

/*LINE WITH LABEL AND ICON*/

angular
  .module('webApp')
  .directive('cIconLabel', cIconLabel);


function cIconLabel() {
  return {
    restrict: 'E',
    scope: {
      iconClass: "=iconClass",
      text: "=text",
      textClass: "=textClass"
    },
    replace: "true",
    templateUrl: 'templates/components/c-icon-label.tpl.html'
  }
};
})();

