#!/usr/bin/env python

from collections import defaultdict
import re
import sys
import json
import copy

# root operators
root_operators = set(['LocalMultiwayProducer',
                      'CollectProducer',
                      'RecoverProducer',
                      'ShuffleProducer',
                      'BroadcastProducer',
                      'SinkRoot',
                      'DbInsert',
                      'EOSController'
                      ])

# By default, all operators have no children
children = defaultdict(list)
# Populate the list for all operators that do have children
children['CollectProducer'] = ['arg_child']
children['RecoverProducer'] = ['arg_child']
children['EOSController'] = ['arg_child']
children['IDBInput'] = [
    'arg_initial_input', 'arg_iteration_input', 'arg_eos_controller_input']
children['RightHashJoin'] = ['arg_child1', 'arg_child2']
children['RightHashCountingJoin'] = ['arg_child1', 'arg_child2']
children['SymmetricHashJoin'] = ['arg_child1', 'arg_child2']
children['LocalMultiwayProducer'] = ['arg_child']
children['MultiGroupByAggregate'] = ['arg_child']
children['SingleGroupByAggregate'] = ['arg_child']
children['ShuffleProducer'] = ['arg_child']
children['DbInsert'] = ['arg_child']
children['Aggregate'] = ['arg_child']
children['Apply'] = ['arg_child']
children['Filter'] = ['arg_child']
children['UnionAll'] = ['arg_children']
children['Merge'] = ['arg_children']
children['ColumnSelect'] = ['arg_child']
children['SymmetricHashCountingJoin'] = ['arg_child1', 'arg_child2']
children['BroadcastProducer'] = ['arg_child']
children['HyperShuffleProducer'] = ['arg_child']
children['SinkRoot'] = ['arg_child']
children['DupElim'] = ['arg_child']
children['Rename'] = ['arg_child']


# deserialize json
def read_json(filename):
    with open(filename, 'r') as f:
        return json.load(f)


# print json
def pretty_json(obj):
    return json.dumps(obj, sort_keys=True, indent=4, separators=(',', ':'))


# get operator name type mapping
def name_type_mapping(query_plan_file):
    plan = read_json(query_plan_file)
    fragments = plan['fragments']
    mapping = dict()
    for fragment in fragments:
        for operator in fragment['operators']:
            if operator['op_name'] in mapping:
                print >> sys.stderr, "       dup names "
                sys.exit(1)
            else:
                mapping[operator['op_name']] = operator['op_type']
    return mapping


# build parent mapping
def get_parent(fragment):
    ret = dict()
    for operator in fragment['operators']:
        for field in children[operator['op_type']]:
            if not isinstance(operator[field], list):
                ret[operator[field]] = operator['op_name']
            else:
                for child in operator[field]:
                    ret[child] = operator['op_name']
    return ret


# build child list dictionary
def get_children(fragment):
    ret = defaultdict(list)
    for operator in fragment['operators']:
        for field in children[operator['op_type']]:
            if not isinstance(operator[field], list):
                ret[operator['op_name']].append(operator[field])
            else:
                for op in operator[field]:
                    ret[operator['op_name']].append(op)
    return ret


# build operator state
def build_operator_state(
        op_name, operators, induced_operators,
        children_dict, type_dict, start_time):

    # build state from events
    states = []
    last_state = 'null'
    last_time = 0

    for event in operators[op_name]:
        if last_state != 'null':
            if last_state == 'live':
                state = {
                    'begin': last_time-start_time,
                    'end': event['time']-start_time,
                    'name': 'compute'
                }
            elif last_state == 'hang':
                state = {
                    'begin': last_time-start_time,
                    'end': event['time']-start_time,
                    'name': 'sleep'
                }
            else:
                raise Exception("error in parsing operator state")
            states.append(state)
        last_state = event['message']
        last_time = event['time']

    states.extend(buildWaitStates(op_name, induced_operators, start_time))
    children_ops = []

    for op in children_dict[op_name]:
        children_ops.append(
            build_operator_state(op, operators, induced_operators,
                                 children_dict, type_dict, start_time))

    qf = {
        'type': type_dict[op_name],
        'name': op_name,
        'states': states,
        'children': children_ops
    }

    return qf


def buildWaitStates(op_name, induced_operators, start_time):
    states = []
    last_state = 'null'
    last_time = 0
    for event in induced_operators[op_name]:
        if last_state != "null":
            if last_state == "wait" and event['message'] == 'wake':
                state = {
                    'begin': last_time-start_time,
                    'end': event['time']-start_time,
                    'name': 'wait'
                }
                states.append(state)
        last_state = event['message']
        last_time = event['time']

    return states


def getRecoveryTaskStructure(operators, type_dict):
    for k, v in operators.items():
        if k.startswith("tupleSource_for_"):
            opName1 = k
            type_dict[k] = "TupleSource"
        if k.startswith("recProducer_for_"):
            opName2 = k
            type_dict[k] = "RecoverProducer"
    parent = dict()
    parent[opName1] = opName2
    children_dict = defaultdict(list)
    children_dict[opName2].append(opName1)
    return (parent, children_dict, type_dict)


def getFragmentStatsOnSingleWorker(path, worker_id, query_id,
                                   fragment_id, query_plan_file):
    # get name type mapping
    type_dict = name_type_mapping(query_plan_file)

    # Workers are numbered from 1, not 0
    lines = [line.strip() for line in
             open("%s/worker_%i_profile" % (path.rstrip('/'), worker_id))]

    # parse infomation from each log message
    tuples = [re.findall(
        r'.query_id#(\d*)..([\w(),]*)@(-?\w*)..(\d*).:([\w|\W]*)', line)
        for line in lines]
    tuples = [i[0] for i in tuples if len(i) > 0]
    tuples = [(i[1], {
        'time': long(i[3]),
        'query_id':i[0],
        'name':i[1],
        'fragment_id':i[2],
        'message':i[4]
    }) for i in tuples]

    # filter out unrelevant queries
    tuples = [
        i for i in tuples if int(i[1]['query_id']) == query_id and
        int(i[1]['fragment_id']) == fragment_id]

    if len(tuples) == 0:
        raise Exception("Cannot get profiling information \
                        in %s/worker_%i_profile" % (path, worker_id))

    # group by operator name
    operators = defaultdict(list)
    for tp in tuples:
        operators[tp[0]].append(tp[1])

    # get fragment tree structure
    if fragment_id < 0:
        # recovery tasks
        (parent, children_dict, type_dict) = \
            getRecoveryTaskStructure(operators, type_dict)
    else:
        # normal fragments in the json query plan
        query_plan = read_json(query_plan_file)
        fragment = query_plan['fragments'][fragment_id]
        parent = get_parent(fragment)
        children_dict = get_children(fragment)

    # update parent's events
    induced_operators = defaultdict(list)
    for k, v in operators.items():
        if k in parent:
            for state in v:
                if state['message'] == 'live':
                    new_state = copy.deepcopy(state)
                    new_state['message'] = 'wait'
                    new_state['name'] = parent[k]
                    induced_operators[parent[k]].append(new_state)
                elif state['message'] == 'hang':
                    new_state = copy.deepcopy(state)
                    new_state['message'] = 'wake'
                    new_state['name'] = parent[k]
                    induced_operators[parent[k]].append(new_state)

    # build wait states
    for k, v in induced_operators.items():
        #operators[k].extend(v)
        induced_operators[k] = sorted(induced_operators[k],
                                      key=lambda k: k['time'])
        if type_dict[k] in root_operators:
            start_time = operators[k][0]['time']
            end_time = operators[k][-1]['time']
            break

    # build json
    for k, v in operators.items():
        if type_dict[k] in root_operators:
            data = build_operator_state(k, operators, induced_operators,
                                        children_dict, type_dict, start_time)
            break
    qf_details = {
        'begin': 0,
        'end': end_time-start_time,
        'hierarchy': [data]
    }

    print pretty_json(qf_details)


def generateProfile(path, query_id, fragment_id, query_plan_file):
    # TODO: implement this method
    print "here"


def main(argv):
# Usage
    if len(argv) != 5 and len(argv) != 6:
        print >> sys.stderr, \
            "Usage: %s <log_files_directory> <worker_id> <query_id> \
            <fragment_id> <query_plan_file>" % (argv[0])
        print >> sys.stderr, \
            " or %s <log_files_directory>  <query_id> <fragment_id>\
            <query_plan_file>" % (argv[0])
        print >> sys.stderr, "       log_file_directory "
        print >> sys.stderr, "       worker_id "
        print >> sys.stderr, "       query_id "
        print >> sys.stderr, "       fragment_id"
        print >> sys.stderr, "       query_plan_file "
        sys.exit(1)
    elif len(argv) == 6:
        getFragmentStatsOnSingleWorker(argv[1], int(argv[2]), int(argv[3]),
                                       int(argv[4]), argv[5])
    else:
        generateProfile(argv[1], int(argv[2]), int(argv[3]), argv[4])


if __name__ == "__main__":
    main(sys.argv)
