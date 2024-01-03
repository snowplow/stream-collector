{
  description = "applications for recovering snowplow bad rows";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    flake-utils.inputs.nixpkgs.follows = "nixpkgs";
    devenv.url = "github:cachix/devenv";
    devenv.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = {
    nixpkgs,
    flake-utils,
    devenv,
    ...
  } @ inputs:
    flake-utils.lib.eachDefaultSystem (
      system: let
        pkgs = import nixpkgs {
          inherit system;
          config.allowUnfree = true;
          config.allowUnsupportedSystem = true;
        };
        jre = pkgs.openjdk11;
        sbt = pkgs.sbt.override {inherit jre;};
        coursier = pkgs.coursier.override {inherit jre;};
        metals = pkgs.metals.override {inherit coursier jre;};
      in {
        devShell = devenv.lib.mkShell {
          inherit inputs pkgs;
          modules = [
            {
              packages = [
                jre
                metals
                sbt
                # pkgs.nodePackages.snyk
                pkgs.kubernetes-helm
                # (pkgs.wrapHelm pkgs.kubernetes-helm {plugins = [pkgs.kubernetes-helmPlugins.helm-diff];})
                # pkgs.google-cloud-sdk.withExtraComponents( with pkgs.google-cloud-sdk.components [ gke-gcloud-auth-plugin ]);
                (pkgs.google-cloud-sdk.withExtraComponents [pkgs.google-cloud-sdk.components.gke-gcloud-auth-plugin])
                # pkgs.google-cloud-sdk-gce
              ];
              languages.nix.enable = true;
              pre-commit.hooks = {
                alejandra.enable = true;
                deadnix.enable = true;
                gitleaks = {
                  enable = true;
                  name = "gitleaks";
                  entry = "${pkgs.gitleaks}/bin/gitleaks detect --source . -v";
                };
              };
            }
          ];
        };
      }
    );
}
