// ----------------------------------------------------------------------------
// |  Imports
// ----------------------------------------------------------------------------
var exec = require('cordova/exec');

// ----------------------------------------------------------------------------
// |  Exports
// ----------------------------------------------------------------------------
exports.getDefaultSettings = function() {
  var settings = {
    barcodeTypes: {
      Aztec     : true,
      CodaBar   : true,
      Code39    : true,
      Code93    : true,
      Code128   : true,
      DataMatrix: true,
      EAN8      : true,
      EAN13     : true,
      ITF       : true,
      PDF417    : true,
      QRCode    : true,
      UPCA      : true,
      UPCE      : true,
    },
    detectorSize: {
        width : .5,
        height: .7
    }
  }; 

  return settings;
};

exports.scan = function (p_OnSuccess, p_OnError, p_Settings) {
  startScanning(p_OnSuccess, p_OnError, p_Settings);
};

function startScanning(p_OnSuccess, p_OnError, p_Settings) {
  if (!p_Settings) {
    throw "p_Settings can't be undefined. Use getDefaultSettings() to get a new settings object";
  }

  var enabledDetectorTypes = 0; //The type of detectors which are neabled are represented by an integer;

  var detectionTypes = {
    Code128   : 1   ,
    Code39    : 2   ,
    Code93    : 4   ,
    CodaBar   : 8   ,
    DataMatrix: 16  ,
    EAN13     : 32  ,
    EAN8      : 64  ,
    ITF       : 128 ,
    QRCode    : 256 ,
    UPCA      : 512 ,
    UPCE      : 1024,
    PDF417    : 2048,
    Aztec     : 4096,
  };

  for (var key in p_Settings.barcodeTypes) {
    if (p_Settings.barcodeTypes[key] == true) {
      enabledDetectorTypes += detectionTypes[key];
    }
  }

  var settingArray = [
    enabledDetectorTypes,
    p_Settings.detectorSize.width,
    p_Settings.detectorSize.height
  ];

  
  exec(p_Result => {
    p_OnSuccess(p_Result[0]);
  }, p_OnError, 'cordova-plugin-google-mobile-vision-barcode-scanner','startScan',settingArray);
};


// // JavaScript Document
// CDV = ( typeof CDV == 'undefined' ? {} : CDV );
// var cordova = window.cordova || window.Cordova;

// function GMVBarcodeScanner() {

// }

// GMVBarcodeScanner.prototype.scan = function(params, callback) {

//     // Default settings. Scan every barcode type.
//     var settings = {
//         types: {
//             Code128: true,
//             Code39: true,
//             Code93: true,
//             CodaBar: true,
//             DataMatrix: true,
//             EAN13: true,
//             EAN8: true,
//             ITF: true,
//             QRCode: true,
//             UPCA: true,
//             UPCE: true,
//             PDF417: true,
//             Aztec: true
//         },
//         detectorSize: {
//             width: .5,
//             height: .7
//         }
//     };

//     for(var key in params) {
//         if(params.hasOwnProperty(key) && settings.hasOwnProperty(key)) {
//             settings[key] = params[key];
//         }
//     }

//     var detectorTypes = 0;

//     // GMVDetectorConstants values allow us to pass an integer sum of all the desired barcode types to the scanner.
//     var detectionTypes = {
//         Code128: 1,
//         Code39: 2,
//         Code93: 4,
//         CodaBar: 8,
//         DataMatrix: 16,
//         EAN13: 32,
//         EAN8: 64,
//         ITF: 128,
//         QRCode: 256,
//         UPCA: 512,
//         UPCE: 1024,
//         PDF417: 2048,
//         Aztec: 4096
//     };

//     for(var key in settings.types) {
//         if(detectionTypes.hasOwnProperty(key) && settings.types.hasOwnProperty(key) && settings.types[key] == true) {
//             detectorTypes+=detectionTypes[key];
//         }
//     }

//     // Order of this settings object is critical. It will be passed in a basic array format and must be in the order shown.
//     var stngs = {
//         //Position 1
//         detectorType: detectorTypes,
//         //Position 2
//         detectorWidth: settings.detectorSize.width,
//         //Position 3
//         detectorHeight: settings.detectorSize.height
//     };

//     var sendSettings = [];
//     for(var key in stngs) {
//         if(stngs.hasOwnProperty(key)) {
//             sendSettings.push(stngs[key]);
//         }
//     }

//     this.sendScanRequest(sendSettings, callback);
// };

// GMVBarcodeScanner.prototype.sendScanRequest = function(settings, callback) {
//     callback = typeof callback == "function" ? callback : function() {};
//     cordova.exec(function (data) {
//             callback(null, data[0]);
//         },
//         function (err){
//             switch(err[0]) {
//                 case null:
//                 case "USER_CANCELLED":
//                     callback({cancelled: true, message: "The scan was cancelled."});
//                     break;
//                 case "SCANNER_OPEN":
//                     callback({cancelled: false, message: "Scanner already open."});
//                     break;
//                 default:
//                     callback({cancelled: false, message: err});
//                     break;
//             }
//         },'cordova-plugin-google-mobile-vision-barcode-scanner','startScan',settings);
// };


// GMVBarcodeScanner.install = function() {
//     if (!window.plugins) {
//         window.plugins = {};
//     }

//     window.plugins.GMVBarcodeScanner = new GMVBarcodeScanner();

//     //Supporting legacy API.
//     CDV.scanner = window.plugins.GMVBarcodeScanner;

//     return window.plugins.GMVBarcodeScanner;
// };

// cordova.addConstructor(GMVBarcodeScanner.install);