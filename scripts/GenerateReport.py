'''
USAGE: Python GenerateReport.py <log file name>
Use Python3 otherwise it won't work
'''

# Log file format constants
TIME_FORMAT_STRING 	= '%a %b %d %H:%M:%S %Z %Y'
DATE_TIME_INDEX 	= 0

TIMEOFFSET_INDEX	= 1
CHUNK_NUM_INDEX 	= 2
CHUNK_QUALITY_INDEX = 3
CHUNK_LEN_INDEX 	= 4
CHUNK_HASH_INDEX 	= 5

# Used for compose buffering event
BUFFER_CHUNK_IDX 	= 0
BUFFER_SPAN_IDX		= 1


# Each chunk contains 2000ms of video except the last one
CHUNK_TIME = 2000

class DownloadReport:

	def __init__(self, filename):

		# Metadata about the file
		self.log_filename = filename
		self.total_chunk = 0
		

		# Events list
		self.event_list = []

		self.start_time = None
		self.finish_time = None
		self.rebuffer_event_list = []
		self.quality_sum = 0.0
		self.chunk_count = 0

		# Buffering related stats
		self.initial_buffer_time = 0
		self.buffer_count = 0
		self.buffer_stats = []

		self.parse_log_file()
		self.compute_statistics()
		self.generate_report()


	# TODO
	def validate_chunk_hash(self, chunk_num, chunk_quality, chunk_hash):
		# An assert that verifies the chunk_hash matches real hash
		pass


	# Iterate through the log file and parse each line for stats
	def parse_log_file(self):
		log_fd = open(self.log_filename)

		for index, line in enumerate(log_fd):
			self.parse_log_line(index, line)


	# Line parser.
	def parse_log_line(self, index, log_line):
		
		line_infos = log_line.split(",")

		# Extract starting time from first line and continue
		if index == 0:
			self.start_time = int(line_infos[TIMEOFFSET_INDEX])
			return


		event_map = {
		'timeoffset'	: int(line_infos[TIMEOFFSET_INDEX]),
		'chunk_num'		: int(line_infos[CHUNK_NUM_INDEX].split(': ')[1]),
		'chunk_quality'	: int(line_infos[CHUNK_QUALITY_INDEX].split(': ')[1]),
		'chunk_len'		: int(line_infos[CHUNK_LEN_INDEX].split(': ')[1]),
		'chunk_hash'	: line_infos[CHUNK_HASH_INDEX].split(': ')[1][0:-1]
		}

		# TODO: Validate hash
		self.validate_chunk_hash(
			event_map['chunk_num'], 
			event_map['chunk_quality'], 
			event_map['chunk_hash'])

		# Load the new event into the list for future computation
		self.event_list.append(event_map)
		self.chunk_count += 1

		# Reached last chunk, record the finish time
		if self.chunk_count == self.total_chunk:
			self.finish_time = log_line[TIMEOFFSET_INDEX]


	def compute_statistics(self):
		prev_event_time = self.start_time
		prev_buffer_time = self.start_time
		prev_buffer_chunk = 0

		for event in self.event_list:

			# If things going smoothly from start to end, we compute buffering based
			# on starting time. If there's buffering event in the middle, we compute
			# the buffering time in respect to the previous buffering event
			buffer_elapse = event['timeoffset'] - (prev_buffer_time + (event['chunk_num'] - prev_buffer_chunk) * CHUNK_TIME)


			is_buffer = True if buffer_elapse > 0 else False

			# For initial buffering stat only
			if prev_event_time == self.start_time:
				self.initial_buffer_time = event['timeoffset'] - prev_event_time
				prev_buffer_time = event['timeoffset']
				prev_buffer_chunk = event['chunk_num']

			else:
				# For following buffering event, gather the chunk that was buffered and
				# the buffering time elapse
				if is_buffer:
					self.buffer_count += 1
					self.buffer_stats.append([event['chunk_num'], buffer_elapse])
					prev_buffer_time = event['timeoffset']
					prev_buffer_chunk = event['chunk_num']

			# Add to the quality sum
			self.quality_sum += event['chunk_quality']
			prev_event_time = event['timeoffset']


	def generate_report(self):
		print('Start Time: {}'.format(self.start_time))
		print('Finish Time: {}'.format(self.finish_time))
		print('Average Quality: {}'.format(self.quality_sum/self.chunk_count))

		# Iterate through the buffering event and compute the buffering statistics
		print('Initial Buffering: {}'.format(self.initial_buffer_time))
		print('Buffering event occured {} times'.format(self.buffer_count))
		for index, buffer_event in enumerate(self.buffer_stats):
			print('\tOccurance {} at Chunk {}, buffered for {} ms'.format( 
				index + 1, buffer_event[BUFFER_CHUNK_IDX], buffer_event[BUFFER_SPAN_IDX]))

import sys
if __name__=="__main__":
	print(sys.argv)
	DownloadReport(sys.argv[1])





# dt_obj = datetime.strptime('Wed Feb 26 15:29:01 PST 2020', '%a %b %d %H:%M:%S %Z %Y').strftime('%s')
