# -*- mode: ruby -*-
# vi: set ft=ruby :

name = "civil-ci"
mem = 512

Vagrant.configure("2") do |config|

  config.vm.box     = "ubuntu"
  config.vm.box_url = "http://files.vagrantup.com/precise64.box"
  config.vm.hostname = name
  config.vm.synced_folder "./", "/var/app"

  config.vm.provision "shell", path: "./tools/bootstrap-vagrant"

  config.vm.provider :virtualbox do |vb|
    modifyvm_args = ['modifyvm', :id]
    modifyvm_args << "--name" << name
    modifyvm_args << "--memory" << mem
    # Isolate guests from host networking.
    modifyvm_args << "--natdnsproxy1" << "on"
    modifyvm_args << "--natdnshostresolver1" << "on"
    vb.customize(modifyvm_args)
  end

end
