define([
    '$',
    'src/utils/masker',
    'src/modules/form/payment/displayCardImg'
], function ($, masker, displayCardImg) {
    'use strict';

    var CREDIT_CARD_NUMBER_ELEM = document.querySelector('.js-credit-card-number');

    /**
     * add listeners for the credit card elem for masker and display credit card image interactivity
     */
    var addPaymentListeners = function () {
        $(CREDIT_CARD_NUMBER_ELEM).on('keyup blur', masker(' ', 4));

        $(CREDIT_CARD_NUMBER_ELEM).on('keyup blur', function (e) {
            var input = e && e.target;
            displayCardImg(input.value);
        });
    };

    return {
        addPaymentListeners: addPaymentListeners
    };
});
