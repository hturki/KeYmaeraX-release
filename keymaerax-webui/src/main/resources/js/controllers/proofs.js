/**
 * Controllers for proof lists and proof information pages.
 */
angular.module('keymaerax.controllers').controller('ModelProofCreateCtrl', function ($scope, $http,
    $routeParams, $location, sessionService, spinnerService) {
  /** User data and helper functions. */
  $scope.user = {
    /** Returns true if the user is a guest, false otherwise. */
    isGuest: function() { return sessionService.isGuest(); }
  };

  /** Create a new proof for model 'modelId' with 'proofName' and 'proofDescription' (both optional: empty ""). */
  $scope.createProof = function(modelId, proofName, proofDescription) {
      var uri     = 'models/users/' + sessionService.getUser() + '/model/' + modelId + '/createProof'
      var dataObj = {proofName: proofName, proofDescription: proofDescription}

      $http.post(uri, dataObj).
          success(function(data) {
              var proofid = data.id
              // we may want to switch to ui.router
              $location.path('proofs/' + proofid);
          }).
          error(function(data, status, headers, config) {
              console.log('Error starting new proof for model ' + $routeParams.modelId)
          });
  };

  /** Opens the last proof (finished or not) of this model. */
  $scope.openLastProof = function(modelId) {
    $http.get('models/users/' + sessionService.getUser() + "/model/" + modelId + "/proofs").then(function(response) {
      if (response.data.length > 0) {
        $location.path('proofs/' + response.data[response.data.length-1].id);
      }
    });
  };

  /** Creates a new proof from the model's tactic (if any). */
  $scope.proveFromTactic = function(modelId) {
    spinnerService.show('modelListProofLoadingSpinner');
    var uri     = 'models/users/' + sessionService.getUser() + '/model/' + modelId + '/createTacticProof'
    $http.post(uri, {}).success(function(data) {
      var proofId = data.id;
      $location.path('proofs/' + proofId);
    }).finally(function() { spinnerService.hide('modelListProofLoadingSpinner'); });
  }

  $scope.delayedProveFromTactic = function(modelId) {
    return function() { $scope.proveFromTactic(modelId); }
  }

  $scope.$emit('routeLoaded', {theview: '/models/:modelId/proofs/create'})
});

/* Polling function to obtain proof status, used in proof lists to update the status in the list */
var pollProofStatus = function(proof, userId, http) {
   setTimeout(function() {
      http.get('proofs/user/' + userId + '/' + proof.id + '/status')
              .success(function(data) {
          if (data.status == undefined) {
            console.log("Continue polling proof status");
            pollProofStatus(proof, userId, http);
          } else if (data.status == 'loading') {
            console.log("Continue polling proof status");
            pollProofStatus(proof, userId, http);
          } else if (data.status == 'loaded') {
            console.log("Received proof status " + data.status);
            proof.loadStatus = data.status
          } else if(data.status == 'Error') {
            console.log("Error: " + data.error)
            showCaughtErrorMessage($uibModal, data, "Error while polling proof status")
          }
          else {
            console.error("Received unknown proof status " + data.status)
            showClientErrorMessage($uibModal, "Received unknown proof status " + data.status);
          }
      }).
      error(function(data, status, headers, config) {
            showCaughtErrorMessage($uibModal, data, "Unable to poll proof status.")
      });
  }, 1000);
}

/* Proof list (those of an individual model if the route param modelId is defined, all proofs otherwise) */
angular.module('keymaerax.controllers').controller('ProofListCtrl', function (
    $scope, $http, $location, $routeParams, $route, $uibModal, FileSaver, Blob, spinnerService, sessionService) {
  $scope.modelId = $routeParams.modelId;
  $scope.userId = sessionService.getUser();

  $scope.intro.introOptions = {
    steps:[
    {
        element: '#proofsarchiving',
        intro: "Extract all proofs into .kyx archives.",
        position: 'bottom'
    },
    {
        element: '#proofsactions',
        intro: "Continue, inspect, export, or delete proofs here.",
        position: 'bottom'
    }
    ],
    showStepNumbers: false,
    exitOnOverlayClick: true,
    exitOnEsc:true,
    nextLabel: 'Next', // could use HTML in labels
    prevLabel: 'Previous',
    skipLabel: 'Exit',
    doneLabel: 'Done'
  }

  $scope.openPrf = function(proofId) {
      $location.path('/proofs/' + proofId)
  }

  $scope.deleteProof = function(proof) {
    $http.post('user/' + $scope.userId + "/proof/" + proof.id + "/delete").success(function(data) {
       $route.reload();
    });
  };

  $scope.loadProof = function(proof) {
    proof.loadStatus = 'loading'
    $http.get('proofs/user/' + $scope.userId + "/" + proof.id).success(function(data) {
      proof.loadStatus = data.loadStatus
      // when server loads proof itself asynchronously
      if (data.loadStatus == 'loading') {
        console.log("Start polling proof status");
        pollProofStatus(proof, $scope.userId, $http);
      } else if(data.loadStatus == 'Error') {
          showMessage($uibModal, "Error encountered while attempting to load proof")
      }
    }).
    error(function(data, status, headers, config) {
      // TODO check that it is a time out
      console.log("Start polling proof status");
      //@TODO does this mean that there isn't necessarily an error here? Confused.
//        showErrorMessage($uibModal, "Encountered error shile trying to poll proof status.")
      pollProofStatus(proof, $scope.userId, $http);
    });
  }

  //@todo duplicate with provingawesome.js downloadTactic
  $scope.downloadTactic = function(proof) {
    $http.get("/proofs/user/" + $scope.userId + "/" + proof.id + "/extract").then(function(response) {
      var data = new Blob([response.data.tacticText], { type: 'text/plain;charset=utf-8' });
      FileSaver.saveAs(data, proof.name + '.kyt');
    });
  }

  //@todo duplicate with provingawesome.js downloadLemma
  $scope.downloadLemma = function(proof) {
    $http.get("/proofs/user/" + $scope.userId + "/" + proof.id + "/lemma").then(function(response) {
      var data = new Blob([response.data.fileContents], { type: 'text/plain;charset=utf-8' });
      FileSaver.saveAs(data, proof.name + '.kyp');
    });
  }

  //@todo duplicate with provingawesome.js downloadProofArchive
  $scope.downloadPartialProof = function(proof) {
    $http.get("/proofs/user/" + $scope.userId + "/" + proof.id + "/download").then(function(response) {
      var data = new Blob([response.data.fileContents], { type: 'text/plain;charset=utf-8' });
      FileSaver.saveAs(data, proof.name + '.kyx');
    });
  }

  $scope.openTactic = function (proofid) {
    var modalInstance = $uibModal.open({
      templateUrl: 'partials/prooftacticdialog.html',
      controller: 'ProofTacticDialogCtrl',
      size: 'fullscreen',
      resolve: {
        userid: function() { return $scope.userId; },
        proofid: function() { return proofid; },
        title: function() { return "Tactic"; },
        message: function() { return undefined; }
      }
    });
  };

  currentDateString = function() {
    var today = new Date();
    var dd = today.getDate();
    var mm = today.getMonth() + 1; //@note January is 0
    var yyyy = today.getFullYear();

    if(dd < 10) dd = '0' + dd
    if(mm < 10) mm='0'+mm
    return mm + dd + yyyy;
  }

  $scope.downloadModelProofs = function(modelId) {
    spinnerService.show('proofExportSpinner');
    $http.get("/models/user/" + $scope.userId + "/model/" + modelId + "/downloadProofs").then(function(response) {
      var data = new Blob([response.data.fileContents], { type: 'text/plain;charset=utf-8' });
      FileSaver.saveAs(data, modelId + '_' + currentDateString() + '.kyx');
    })
    .finally(function() { spinnerService.hide('proofExportSpinner'); });
  }

  $scope.downloadAllProofs = function() {
    spinnerService.show('proofExportSpinner');
    $http.get("/proofs/user/" + $scope.userId + "/downloadAllProofs").then(function(response) {
      var data = new Blob([response.data.fileContents], { type: 'text/plain;charset=utf-8' });
      FileSaver.saveAs(data, 'proofs_'+ currentDateString() +'.kyx');
    })
    .finally(function() { spinnerService.hide('proofExportSpinner'); });
  }

  //Load the proof list and emit as a view.
  if ($scope.modelId !== undefined) {
    $http.get('models/users/' + $scope.userId + "/model/" + $scope.modelId + "/proofs").success(function(data) {
      $scope.proofs = data;
    });
    $scope.$emit('routeLoaded', {theview: 'proofs'});
  } else {
    $http.get('models/users/' + $scope.userId + "/proofs").success(function(data) {
      $scope.proofs = data;
    });
    $scope.$emit('routeLoaded', {theview: 'allproofs'});
  }

});

angular.module('keymaerax.controllers').controller('ProofTacticDialogCtrl', function (
    $scope, $http, $uibModalInstance, userid, proofid, title, message) {
  $scope.title = title;
  $scope.message = message;
  $scope.tactic = {
    tacticBody: "",
    proofId: proofid
  };

  $http.get("proofs/user/" + userid + "/" + proofid + "/tactic").then(function(response) {
      $scope.tactic.tacticBody = response.data.tacticText;
  });

  $scope.ok = function () { $uibModalInstance.close(); };
});
