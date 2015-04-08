/*global guardian:true */
define([
    'src/utils/cookie',
    'src/modules/analytics/ga',
    'src/modules/analytics/ophan',
    'src/modules/analytics/omniture',
    'src/modules/analytics/crazyegg'
], function (
    cookie,
    googleAnalytics,
    ophanAnalytics,
    omnitureAnalytics,
    crazyegg
) {

    var ANALYTICS_OFF_KEY = 'ANALYTICS_OFF_KEY';

    function init() {
        if (cookie.getCookie(ANALYTICS_OFF_KEY)) {
            guardian.analyticsEnabled = false;
        }

        if (guardian.analyticsEnabled) {
            ophanAnalytics.init();
            omnitureAnalytics.init();
            googleAnalytics.init();

            if(!guardian.isDev) {
                crazyegg.load();
            }

        }
    }

    return {
        init: init
    };
});
