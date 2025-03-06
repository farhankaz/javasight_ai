'use client'

import { useState, useEffect } from 'react'
import { Spinner } from './ui/spinner'
import { toast } from 'react-hot-toast'
import ProjectImportProgress from './ProjectImportProgress'

interface ImportGithubProjectModalProps {
  isOpen: boolean
  onClose: () => void
  onSuccess: () => void
}

export default function ImportGithubProjectModal({
  isOpen,
  onClose,
  onSuccess
}: ImportGithubProjectModalProps) {
  const [githubUrl, setGithubUrl] = useState('')
  const [projectName, setProjectName] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [importId, setImportId] = useState<string | null>(null)
  const [urlError, setUrlError] = useState('')

  // Reset form when modal is opened/closed
  useEffect(() => {
    if (isOpen) {
      setGithubUrl('')
      setProjectName('')
      setImportId(null)
      setUrlError('')
      setIsSubmitting(false)
    }
  }, [isOpen])

  // Auto-extract project name from GitHub URL
  useEffect(() => {
    if (githubUrl) {
      // Try to extract repository name from GitHub URL
      const match = githubUrl.match(/github\.com\/[\w.-]+\/([\w.-]+)(\.git)?/)
      if (match && match[1]) {
        setProjectName(match[1])
      }
    }
  }, [githubUrl])

  // Validate GitHub URL
  const validateGithubUrl = (url: string): boolean => {
    const githubUrlPattern = /^https:\/\/github\.com\/[\w.-]+\/[\w.-]+(\.git)?$/
    const isValid = githubUrlPattern.test(url)
    
    if (!isValid) {
      setUrlError('Please enter a valid GitHub repository URL (e.g., https://github.com/username/repo)')
    } else {
      setUrlError('')
    }
    
    return isValid
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    
    if (!validateGithubUrl(githubUrl)) {
      return
    }
    
    try {
      setIsSubmitting(true)
      
      const response = await fetch('/api/projects', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          projectName,
          githubUrl,
          projectContext: '', // Optional context can be added later
        }),
      })
      
      const data = await response.json()
      
      if (!response.ok) {
        throw new Error(data.error || 'Failed to import GitHub project')
      }
      
      setImportId(data.importId)
      toast.success('GitHub import started successfully')
    } catch (error) {
      console.error('Error importing GitHub project:', error)
      toast.error(error instanceof Error ? error.message : 'Failed to import GitHub project')
    } finally {
      setIsSubmitting(false)
    }
  }

  const handleImportComplete = () => {
    onSuccess()
    onClose()
  }

  // If modal is not open, don't render anything
  if (!isOpen) return null

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="relative w-full max-w-md bg-white rounded-lg p-6 shadow-xl">
        <button
          onClick={onClose}
          className="absolute top-3 right-3 text-gray-400 hover:text-gray-600"
          disabled={isSubmitting}
        >
          <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <line x1="18" y1="6" x2="6" y2="18"></line>
            <line x1="6" y1="6" x2="18" y2="18"></line>
          </svg>
        </button>

        <h2 className="text-xl font-semibold mb-4">Import GitHub Project</h2>
        
        {!importId ? (
          // Import form
          <form onSubmit={handleSubmit}>
            <div className="mb-4">
              <label htmlFor="githubUrl" className="block text-sm font-medium text-gray-700 mb-1">
                GitHub Repository URL
              </label>
              <input
                type="text"
                id="githubUrl"
                className={`w-full p-2 border rounded ${urlError ? 'border-red-500' : 'border-gray-300'}`}
                placeholder="https://github.com/username/repository"
                value={githubUrl}
                onChange={(e) => setGithubUrl(e.target.value)}
                disabled={isSubmitting}
                required
              />
              {urlError && <p className="mt-1 text-sm text-red-600">{urlError}</p>}
            </div>

            <div className="mb-6">
              <label htmlFor="projectName" className="block text-sm font-medium text-gray-700 mb-1">
                Project Name
              </label>
              <input
                type="text"
                id="projectName"
                className="w-full p-2 border border-gray-300 rounded"
                placeholder="Project name"
                value={projectName}
                onChange={(e) => setProjectName(e.target.value)}
                disabled={isSubmitting}
                required
              />
            </div>
            
            <div className="flex justify-end">
              <button
                type="button"
                onClick={onClose}
                className="px-4 py-2 text-sm font-medium text-gray-700 bg-gray-100 rounded-md hover:bg-gray-200 mr-2"
                disabled={isSubmitting}
              >
                Cancel
              </button>
              <button
                type="submit"
                className="px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-md hover:bg-blue-700 flex items-center"
                disabled={isSubmitting}
              >
                {isSubmitting ? (
                  <>
                    <Spinner className="mr-2" />
                    Importing...
                  </>
                ) : 'Import Project'}
              </button>
            </div>
          </form>
        ) : (
          // Progress tracking component
          <ProjectImportProgress 
            importId={importId} 
            onComplete={handleImportComplete} 
          />
        )}
      </div>
    </div>
  )
}