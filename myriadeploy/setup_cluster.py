#!/usr/bin/env python

import myriadeploy

import subprocess
import sys

def host_port_list(workers):
    return [str(x) + ':' + str(y) for (x, y) in workers]

def make_catalog(config):
    """Creates a Myria catalog (running the Java program to do so) from the
given deployment configuration."""
    # Extract the needed arguments from config
    description = config['description']
    nodes = config['nodes']

    # Remove the old catalog, if it exists.
    args = ["rm", "-rf", description]
    subprocess.call(args)

    # Create a new one.
    args = ["./run_catalog_maker.sh", \
            description, \
            str(len(nodes))]
    args += host_port_list(nodes)
    if subprocess.call(args):
        print >> sys.stderr, "error making the Catalog"
        sys.exit(1)

def remote_mkdir(hostname, dirname, username):
    if hostname != 'localhost':
        args = ["ssh", "%s@%s" % (username, hostname), "mkdir", "-p", dirname]
    else:
        args = ["mkdir", "-p", dirname]
    return subprocess.call(args)

def copy_master_catalog(hostname, dirname, path, username):
    local_path = "%s/%s" % (dirname, "master.catalog")
    if hostname != 'localhost':
        remote_path = "%s@%s:%s/%s-files/%s" % (username, hostname, path, dirname, dirname)
    else:
        remote_path = "%s/%s-files/%s" % (path, dirname, dirname)
    args = ["rsync", "-aLvz", local_path, remote_path]
    return subprocess.call(args)

def copy_worker_catalog(hostname, dirname, path, i, username):
    local_path = "%s/worker_%d" % (dirname, i)
    if hostname != 'localhost':
        remote_path = "%s@%s:%s/%s-files/%s" % (username, hostname, path, dirname, dirname)
    else:
        remote_path = "%s/%s-files/%s" % (path, dirname, dirname)
    args = ["rsync", "-aLvz", local_path, remote_path]
    return subprocess.call(args)

def copy_catalogs(config):
    """Copies the master and worker catalogs to the remote hosts."""
    description = config['description']
    path = config['path']
    master = config['master']
    workers = config['workers']
    username = config['username']

    # Make directories on master
    (hostname, _) = master
    if remote_mkdir(hostname, "%s/%s-files/%s" \
            % (path, description, description), username):
        raise Exception("Error making directory on master %s" \
                % (hostname,))
    # Copy files to master
    if copy_master_catalog(hostname, description, path, username):
        raise Exception("Error copying master.catalog to %s" % (hostname,))

    for (i, (hostname, _)) in enumerate(workers):
        # Workers are numbered from 1, not 0
        worker_id = i + 1

        # Make directories on the worker
        if remote_mkdir(hostname, "%s/%s-files/%s" \
                % (path, description, description), username):
            raise Exception("Error making directory on worker %d %s" \
                    % (worker_id, hostname))
        # Copy the files to the worker
        if copy_worker_catalog(hostname, description, path, worker_id, username):
            raise Exception("Error copying worker.catalog to %s " % (hostname,))

def copy_distribution(config):
    "Copy the distribution (jar and libs and conf) to compute nodes."
    nodes = config['nodes']
    description = config['description']
    path = config['path']
    username = config['username']

    for (hostname, _) in nodes:
        if hostname != 'localhost':
            remote_path = "%s@%s:%s/%s-files" % (username, hostname, path, description)
        else:
            remote_path = "%s/%s-files" % (path, description)
        to_copy = ["myriad-0.1.jar", "sqlite4java-282", "conf"]
        args = ["rsync", "-aLvz"] + to_copy + [remote_path]
        if subprocess.call(args):
            raise Exception("Error copying distribution to %s" % (hostname,))

def main(argv):
    # Usage
    if len(argv) != 2:
        print >> sys.stderr, "Usage: %s <deployment.cfg>" % (argv[0])
        print >> sys.stderr, "       deployment.cfg: a configuration file modeled after deployment.cfg.sample"
        sys.exit(1)

    config = myriadeploy.read_config_file(argv[1])

    # Step 1: make the Catalog
    make_catalog(config)

    # Step 2: Copy each catalog over
    copy_catalogs(config)

    # Step 3: Copy over java, libs, myriad
    copy_distribution(config)

if __name__ == "__main__":
    main(sys.argv)