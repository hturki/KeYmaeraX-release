angular.module('keymaerax.controllers').controller('LoginCtrl',
  function ($scope, $uibModal, $http, sessionService) {
    $scope.defaultLogin = function() { login("guest", "guest") }

    $scope.username = ""
    $scope.password = ""

    $scope.processLogin = function() { $scope.login($scope.username, $scope.password, false); }

    $scope.processRegistration = function() {
      var modeModalInstance = $uibModal.open({
        templateUrl: 'partials/usermodedialog.html',
        controller: 'UserModeDialogCtrl',
        backdrop: "static",
        size: 'md'
      })
      modeModalInstance.result.then(function(selectedMode) {
        var modalInstance = $uibModal.open({
          templateUrl: 'partials/license_dialog.html',
          controller: 'LicenseDialogCtrl',
          backdrop: "static",
          size: 'lg'
        });
        modalInstance.result.then(function() {
          $http.post("/user/" + $scope.username + "/" + $scope.password + "/mode/" + selectedMode)
            .then(function(response) {
              if (response.data.success === true) { $scope.login($scope.username, $scope.password, true); }
              else { showMessage($uibModal, "Registration failed", "Sorry, user name is already taken. Please choose a different name."); }
            });
        });
      })
    }

    $scope.login = function(username, password, firstTime) {
      $http.get("/user/" + username + "/" + password + "/mode/0")
      .then(function(response) {
        if(response.data.type == "LoginResponse") {
          if(response.data.success) {
            sessionService.setToken(response.data.sessionToken);
            sessionService.setUser(response.data.value);
            sessionService.setUserAuthLevel(response.data.userAuthLevel);
            document.location.href = firstTime ? "/dashboard.html?#/modelsFirstTime" : "/dashboard.html?#/models";
          } else {
            showMessage($uibModal, "Login failed", "Please check user name and password. Or choose a new user name and password, and click 'register' to register a new local proof storage account.");
          }
        }
      }).catch(function(data, status) {
        showMessage($uibModal, "Login failed", "Please check user name and password. Or choose a new user name and password, and click 'register' to register a new local proof storage account.");
      });
    }
  });

angular.module('keymaerax.controllers').controller('UserModeDialogCtrl',
  ['$scope', '$uibModalInstance', function($scope, $uibModalInstance) {

  $scope.selectedMode = "0";

  $scope.selectMode = function() { $uibModalInstance.close($scope.selectedMode); };
  }]);
